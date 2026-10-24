package cc.taixue.suggestor

import com.google.gson.JsonElement
import java.nio.ByteBuffer
import java.util.UUID

object Wire {
    const val CHANNEL = "suggestor:main"
    const val MAX_TOTAL = 512*1024
    const val CHUNK = 24000
    const val HEADER = 32
    private const val MAGIC = 0x53554731
    data class Message(val protocol: Int = 1, val session: String, val type: String, val data: JsonElement)
    fun message(session: String,type: String,data: Any) = Message(1,session,type,Json.gson.toJsonTree(data))
    fun encode(message: Message): List<ByteArray> {
        val bytes = Json.gson.toJson(message).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TOTAL) { "Message too large" }
        val id=UUID.randomUUID(); val count=(bytes.size+CHUNK-1)/CHUNK
        return (0 until count).map { index ->
            val start=index*CHUNK; val size=minOf(CHUNK,bytes.size-start)
            ByteBuffer.allocate(HEADER+size).putInt(MAGIC).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
                .putInt(index).putInt(count).putInt(bytes.size).put(bytes,start,size).array()
        }
    }
    class Assembler {
        private var id: UUID? = null
        private var parts = arrayOfNulls<ByteArray>(0)
        private var total=0; private var received=0; private var started=0L
        fun accept(frame: ByteArray): Message? {
            require(frame.size in HEADER+1..HEADER+CHUNK) { "Invalid frame size" }
            val b=ByteBuffer.wrap(frame); require(b.int==MAGIC) { "Unknown protocol" }
            val next=UUID(b.long,b.long); val index=b.int; val count=b.int; val length=b.int
            require(length in 1..MAX_TOTAL && count==(length+CHUNK-1)/CHUNK && index in 0 until count)
            require(b.remaining()==minOf(CHUNK,length-index*CHUNK))
            if(id==null || System.nanoTime()-started>10_000_000_000L) {
                id=next; parts=arrayOfNulls(count);total=length;received=0;started=System.nanoTime()
            }
            require(id==next && parts.size==count && total==length && parts[index]==null) { "Invalid/interleaved chunks" }
            parts[index]=ByteArray(b.remaining()).also(b::get);received++
            if(received<count)return null
            val data=ByteBuffer.allocate(total);parts.forEach { data.put(it!!) };id=null;parts=arrayOfNulls(0)
            return Json.gson.fromJson(data.array().toString(Charsets.UTF_8),Message::class.java).also {
                require(it.protocol==1 && it.session.length<=100 && it.type.length<=80)
            }
        }
    }
}
