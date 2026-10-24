#!/usr/bin/env python3
"""Real Paper/Fabric/Cloth integration in a new loopback-only game instance.

Uses local cached Minecraft runtimes; never loads production worlds or configurations.
"""
import argparse,signal,functools,hashlib,http.server,json,os,shutil,socket,subprocess,threading,time,urllib.request,uuid,zipfile
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--work-dir',type=Path,required=True)
p.add_argument('--paper-runtime',type=Path,required=True)
p.add_argument('--library-cache',type=Path,required=True)
p.add_argument('--xvfb-runtime',type=Path,required=True)
a=p.parse_args()
project=Path(__file__).resolve().parents[1];root=a.work_dir.resolve()
assert not root.exists(),'Use a new work directory'
root.mkdir(parents=True)
java=Path(os.environ['JAVA_HOME'])/'bin/java'
display_id=next(n for n in range(94,194) if not Path(f'/tmp/.X11-unix/X{n}').exists() and not Path(f'/tmp/.X{n}-lock').exists())
cache=Path.home()/'.gradle/caches'
serverroot=root/'server';serverroot.mkdir()
for directory in ['libraries','cache','versions']:shutil.copytree(a.paper_runtime/directory,serverroot/directory)
shutil.copy2(a.paper_runtime/'cores/paper-26.3-218.jar',serverroot/'server.jar')
(serverroot/'plugins/Suggestor').mkdir(parents=True)
shutil.copy2(project/'server/build/libs/Suggestor-Paper-0.1.0+mc26.3.jar',serverroot/'plugins/Suggestor.jar')
(serverroot/'eula.txt').write_text('eula=true\n')
with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
(serverroot/'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={port}
online-mode=false
white-list=false
enforce-whitelist=false
enforce-secure-profile=false
view-distance=2
simulation-distance=2
spawn-protection=0
level-type=minecraft:flat
generator-settings={{"biome":"minecraft:plains","layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:stone","height":4}}],"features":false}}
generate-structures=false
max-players=8
''')
(serverroot/'ops.json').write_text(json.dumps([{'uuid':str(uuid.UUID(bytes=hashlib.md5(b'OfflinePlayer:PermissionYes').digest(),version=3)),'name':'PermissionYes','level':4,'bypassesPlayerLimit':False}]))
(serverroot/'bukkit.yml').write_text('settings:\n  allow-end: false\n')
httpdir=root/'downloads';httpdir.mkdir()
def artifact(name,kind,version):
    path=httpdir/name
    with zipfile.ZipFile(path,'w') as z:
        if kind=='MOD':z.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'suggested_fixture','version':version,'name':'Fixture Mod','environment':'client','depends':{'minecraft':'26.3'}}))
        if kind=='RESOURCE_PACK':z.writestr('pack.mcmeta',json.dumps({'pack':{'description':'Fixture '+version,'pack_format':75}}))
        if kind=='SHADER_PACK':z.writestr('shaders/fixture.fsh','// fixture '+version)
    return path
assets={
    'mod1':artifact('fixture-1.jar','MOD','1.0'), 'mod2':artifact('fixture-2.jar','MOD','2.0'),
    'pack1':artifact('pack-1.zip','RESOURCE_PACK','1.0'), 'pack2':artifact('pack-2.zip','RESOURCE_PACK','2.0'),
    'shader':artifact('shader.zip','SHADER_PACK','1.0')}
class Handler(http.server.SimpleHTTPRequestHandler):
    def log_message(self,*args):pass
http=http.server.ThreadingHTTPServer(('127.0.0.1',0),functools.partial(Handler,directory=str(httpdir)))
threading.Thread(target=http.serve_forever,daemon=True).start()
ui={'title':'ARCH Fixture 建议','introduction':'服务器下发的安装、更新和移除说明。','accept':'采纳测试建议','ignore':'忽略测试建议',
    'consentTitle':'Fixture Inventory','consentText':'允许此测试服务器接收组件清单。','restart':'Fixture: 请退出游戏并在应用后重新启动。','requiredDisconnect':'Fixture: 必须处理建议后重新连接。'}
serverid=str(uuid.uuid4())
def definition(key,kind,action='ADD',level='SUGGESTED',old=''):
    path=assets[key];identifier='suggested_fixture' if kind=='MOD' else 'fixture.zip'
    return {'id':key,'revision':1,'kind':kind,'action':action,'level':level,'targetId':identifier,'name':key,'reason':'服务器提供的原因 '+key,
            'targetVersion':'2.0' if key.endswith('2') else '1.0','url':f'http://127.0.0.1:{http.server_port}/{path.name}',
            'fileName':'suggested-fixture.jar' if kind=='MOD' else 'fixture.zip','sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
            'size':path.stat().st_size,'fromSha256':old}
def config(suggestions,audience=None,missing='SILENT'):
    return {'serverId':serverid,'handshakeSeconds':8,'decisionSeconds':180,'suggestions':suggestions,'missingClient':{'action':missing,'message':'Fixture Missing Message','title':'Fixture Missing Title','subtitle':'Fixture Missing Subtitle'},'assignments':
        [{'id':'fixture','audience':audience or {'players':['SuggestorTest']},'suggestions':[s['id'] for s in suggestions],'ui':ui}] if suggestions else []}
configpath=serverroot/'plugins/Suggestor/config.json'
configpath.write_text(json.dumps(config([])))
logpath=root/'server.log';log=logpath.open('w')
server=subprocess.Popen([str(java),'-Xms128m','-Xmx1024m','-XX:ActiveProcessorCount=2','-Dterminal.jline=false','-Dterminal.ansi=false','-jar','server.jar','--nogui'],
    cwd=serverroot,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True)
result={'production_services':False,'status':'RUNNING','checks':[]}
xvfb=None
def wait(predicate,seconds=60):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        if predicate():return
        time.sleep(.1)
    raise TimeoutError('Condition timed out')
def check(name,data=None):
    result['checks'].append({'name':name,'status':'PASS','data':data});print('PASS '+name,flush=True)
def reload(suggestions,audience=None,missing='SILENT'):
    configpath.write_text(json.dumps(config(suggestions,audience,missing),ensure_ascii=False));offset=logpath.stat().st_size
    server.stdin.write('suggestor reload\n');server.stdin.flush()
    wait(lambda:'Suggestor 已重载' in logpath.read_bytes()[offset:].decode(errors='replace'),45)
def cached(group,artifact,version):
    return next(x for x in (cache/f'modules-2/files-2.1/{group}/{artifact}/{version}').glob('*/*.jar') if not x.name.endswith('-sources.jar'))
info=json.loads((cache/'fabric-loom/26.3/mojang_minecraft_info.json').read_text())
profile=json.load(urllib.request.urlopen('https://meta.fabricmc.net/v2/versions/loader/26.3/0.20.1/profile/json',timeout=30))
classpath=[]
for lib in info['libraries']:
    allowed=not lib.get('rules')
    for rule in lib.get('rules',[]):
        if rule.get('os',{}).get('name','linux')=='linux':allowed=rule['action']=='allow'
    if allowed and 'artifact' in lib.get('downloads',{}):
        item=lib['downloads']['artifact'];path=a.library_cache/item['url'].rsplit('/',1)[1]
        assert path.exists() and (not item.get('sha1') or hashlib.sha1(path.read_bytes()).hexdigest()==item['sha1']),str(path)
        classpath.append(path)
for lib in profile['libraries']:
    group,name,v=lib['name'].split(':');path=a.library_cache/f'{name}-{v}.jar';assert path.exists(),str(path);classpath.append(path)
classpath.append(cache/'fabric-loom/26.3/minecraft-client.jar')
def native(mode,directory='client',name='SuggestorTest',with_mod=True):
    run=root/directory;(run/'mods').mkdir(parents=True,exist_ok=True)
    for path in [project/'client/build/libs/SuggestorFixture.jar',cached('net.fabricmc.fabric-api','fabric-api','0.165.0+26.3'),
                 cached('me.shedaniel.cloth','cloth-config-fabric','26.3.159')]:shutil.copy2(path,run/'mods'/path.name)
    if with_mod:shutil.copy2(project/'client/build/libs/Suggestor-Fabric-0.1.0+mc26.3.jar',run/'mods/Suggestor.jar')
    (run/'options.txt').write_text('onboardAccessibility:false\npauseOnLostFocus:false\nrenderDistance:2\nsimulationDistance:5\nmaxFps:20\nsoundCategory_master:0.0\ntutorialStep:none\nguiScale:2\n')
    report=run/f'{mode}.json';report.unlink(missing_ok=True)
    command=[str(java),'-Xms128m','-Xmx1024m','-XX:ActiveProcessorCount=3',f'-Dsuggestor.fixture.mode={mode}',f'-Dsuggestor.fixture.report={report}',
        '-cp',os.pathsep.join(map(str,classpath+([] if with_mod else [cached('org.jetbrains.kotlin','kotlin-stdlib','2.4.21')]))),profile['mainClass'],'--username',name,'--version','26.3','--gameDir',str(run),
        '--assetsDir',str(cache/'fabric-loom/assets'),'--assetIndex','26.3-'+info['assetIndex']['id'],'--accessToken','0','--userType','legacy',
        '--width','1000','--height','700','--quickPlayMultiplayer',f'127.0.0.1:{port}']
    env=dict(os.environ,DISPLAY=f':{display_id}',MESA_GL_VERSION_OVERRIDE='4.6',MESA_GLSL_VERSION_OVERRIDE='460',LIBGL_ALWAYS_SOFTWARE='true',ALSOFT_DRIVERS='null',SDL_VIDEO_FORCE_EGL='1')
    with (run/f'{mode}.log').open('w') as output:
        process=subprocess.run(command,cwd=run,env=env,stdout=output,stderr=subprocess.STDOUT,timeout=600)
    assert process.returncode==0,f'Client {mode} failed: {run}/{mode}.log'
    reportdata=json.loads(report.read_text());assert reportdata['status']=='PASS',reportdata
    return reportdata
try:
    wait(lambda:server.poll() is not None or 'Done (' in logpath.read_text(errors='replace'),180)
    assert server.poll() is None,logpath.read_text()[-4000:]
    xvfbroot=a.xvfb_runtime.resolve()
    env=dict(os.environ,LD_LIBRARY_PATH=str(xvfbroot/'usr/lib/x86_64-linux-gnu'))
    command=[str(xvfbroot/'usr/bin/proot'),'-b',str(xvfbroot/'usr/share/X11/xkb')+':/usr/share/X11/xkb',
             '-b',str(xvfbroot/'usr/bin/xkbcomp')+':/usr/bin/xkbcomp',str(xvfbroot/'usr/bin/Xvfb'),f':{display_id}','-screen','0','1000x700x24','-nolisten','tcp','-ac']
    xvfb=subprocess.Popen(command,env=env,stdout=(root/'xvfb.log').open('w'),stderr=subprocess.STDOUT,start_new_session=True)
    wait(lambda:Path(f'/tmp/.X11-unix/X{display_id}').exists() or xvfb.poll() is not None,15)
    assert xvfb.poll() is None,'Xvfb failed'
    check('empty config is silent in real Fabric client',native('empty','empty-client'))
    assert not (serverroot/'plugins/Suggestor/audit.jsonl').exists(),'Empty config collected audit/inventory'
    first=[definition('mod1','MOD',level='REQUIRED'),definition('pack1','RESOURCE_PACK',level='RECOMMENDED'),definition('shader','SHADER_PACK')]
    reload(first)
    check('name selector excludes other players',native('empty','excluded-client','Unselected'))
    check('three artifact types accepted through Cloth UI',native('install'))
    txroot=root/'client/.suggestor/transactions'
    wait(lambda:any(p.exists() for p in txroot.glob('*/result.json')),30)
    applied=[json.loads(p.read_text()) for p in txroot.glob('*/result.json')];assert all(r['success'] for r in applied),applied
    for kind,path in [('mods','suggested-fixture.jar'),('resourcepacks','fixture.zip'),('shaderpacks','fixture.zip')]:assert (root/'client'/kind/path).exists()
    check('embedded child applies after real Minecraft exit',applied)
    second=[definition('mod2','MOD','UPDATE',level='REQUIRED'),definition('pack2','RESOURCE_PACK','UPDATE'),
            definition('shader','SHADER_PACK','REMOVE',old=first[2]['sha256'])]
    reload(second)
    check('restart result and update-remove workflow',native('update'))
    wait(lambda:len(list(txroot.glob('*/result.json')))==2,30)
    assert all(json.loads(p.read_text())['success'] for p in txroot.glob('*/result.json'))
    assert hashlib.sha256((root/'client/mods/suggested-fixture.jar').read_bytes()).hexdigest()==second[0]['sha256']
    assert hashlib.sha256((root/'client/resourcepacks/fixture.zip').read_bytes()).hexdigest()==second[1]['sha256']
    assert not (root/'client/shaderpacks/fixture.zip').exists()
    check('next process loads updated MOD and reports results',native('verify'))
    optional=[definition('shader','SHADER_PACK')]
    reload(optional,{'all':True})
    check('all-player optional suggestion can be ignored',native('ignore','ignore-client'))
    reload(optional,{'permissions':['suggestor.fixture.builder']})
    check('permission selector excludes a player without permission',native('empty','permission-no','PermissionNo'))
    check('permission selector includes permitted player',native('ignore','permission-yes','PermissionYes'))
    required=[definition('shader','SHADER_PACK',level='REQUIRED')]
    reload(required)
    check('required refusal disconnects',native('refuse','refuse-client'))
    check('required inventory refusal is separate from missing-client detection',native('decline','decline-client'))
    reload([],missing='KICK')
    check('installed client is detected without requesting an inventory',native('silent','presence-client'))
    check('missing-client KICK uses configured message even with no suggestions',native('nomod','nomod-client',with_mod=False))
    reload([],missing='REMIND')
    check('missing-client REMIND sends configured title and retains connection',native('reminder','reminder-client',with_mod=False))
    assert 'Fixture Missing Message' in (root/'reminder-client/reminder.log').read_text()
    reload(required,missing='SILENT')
    check('missing-client SILENT sends no reminder and does not disconnect',native('silent','silent-client',with_mod=False))
    auditpath=serverroot/'plugins/Suggestor/audit.jsonl'
    rows=[json.loads(line) for line in auditpath.read_text().splitlines()]
    events={row['event'] for row in rows}
    assert {'inventory','offered','displayed','decision','client_ready','client_receipt','missing_client','client_detected'}<=events,events
    assert any(row['event']=='decision' and row['data']['ignored'] for row in rows)
    check('server audit contains offers decisions and offline completion receipts',sorted(events))
    reload([])
    check('reloaded empty config returns to no-impact mode',native('empty','final-empty-client'))
    result['status']='PASS'
except Exception as e:
    result['status']='FAIL';result['error']=repr(e);raise
finally:
    http.shutdown()
    if server.poll() is None:
        server.stdin.write('stop\n');server.stdin.flush()
        try:server.wait(timeout=45)
        except subprocess.TimeoutExpired:server.terminate();server.wait(timeout=20)
    if xvfb:
        try:os.killpg(xvfb.pid,signal.SIGTERM)
        except ProcessLookupError:pass
    (root/'report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False,indent=2),flush=True)
