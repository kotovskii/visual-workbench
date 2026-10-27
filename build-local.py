"""Build the focused client model-loader optimization against installed NeoForge libraries.

Uses the supplied source's targeted resolver instead of the installed release's
global MODIFY_UNBAKED_MODEL listener. All server logic, integrations, registrations,
resources, and mixin configurations remain byte-identical to the installed release.
"""
from pathlib import Path
import hashlib, io, json, subprocess, zipfile

BASE = Path(__file__).resolve().parent
SERVER = Path(r'C:\GAMES\Minecraft\AutismCraft-Server\AutismCraft_1.21.1')
CLIENT_MODS = Path(r'C:\Users\Point\AppData\Roaming\PrismLauncher\instances\AutismCraft 1.21.1\minecraft\mods')
JDK = Path(r'C:\Program Files\Java\graalvm-jdk-21.0.12+7.1\bin')
VERSION = '21.1.2+autismcraft.1'
BUILD = BASE / 'local-build'
BUILD.mkdir(exist_ok=True)
input_path = BASE / 'local-build-input/VisualWorkbench-v21.1.2-1.21.1-NeoForge.jar'
classpath = [input_path, next(CLIENT_MODS.glob('puzzleslib-*.jar')),
             Path(r'C:\GAMES\Minecraft\DEV\epictweaks-1.21.1\build\moddev\artifacts\neoforge-21.1.229-merged.jar')]
classpath += list((SERVER / 'libraries').glob('**/*.jar'))
classes = BUILD / 'classes'
classes.mkdir(exist_ok=True)
source = BASE/'Common/src/main/java/fuzs/visualworkbench/client/VisualWorkbenchClient.java'
arguments = ['--release', '21', '-proc:none', '-cp', ';'.join(map(str, classpath)), '-d', str(classes), str(source)]
# javac argument file avoids Windows command-line length limits.
argfile = BUILD/'javac.args'
argfile.write_text('\n'.join('"'+a.replace('\\','/').replace('"','\\"')+'"' for a in arguments))
subprocess.run([str(JDK/'javac.exe'), '@'+str(argfile)], check=True)
class_name = 'fuzs/visualworkbench/client/VisualWorkbenchClient.class'
new_class = (classes/class_name).read_bytes()
assert b'MODIFY_UNBAKED_MODEL' not in new_class
assert b'onRegisterBlockStateResolver' in new_class
old = input_path.read_bytes()
stream = io.BytesIO()
with zipfile.ZipFile(io.BytesIO(old)) as a, zipfile.ZipFile(stream,'w') as b:
    for entry in a.infolist():
        data = a.read(entry)
        if entry.filename == class_name:
            data = new_class
        elif entry.filename == 'META-INF/neoforge.mods.toml':
            text = data.decode()
            assert 'version = "21.1.2"' in text and '[21.1.48,)' in text
            data = text.replace('version = "21.1.2"', 'version = "'+VERSION+'"').replace('[21.1.48,)', '[21.1.58,)').encode()
        b.writestr(entry,data)
new = stream.getvalue()
with zipfile.ZipFile(io.BytesIO(old)) as a, zipfile.ZipFile(io.BytesIO(new)) as b:
    assert a.namelist()==b.namelist()
    changed = [n for n in a.namelist() if a.read(n)!=b.read(n)]
    assert set(changed)=={class_name,'META-INF/neoforge.mods.toml'}, changed
output_path = BUILD/('VisualWorkbench-v'+VERSION+'-1.21.1-NeoForge.jar')
output_path.write_bytes(new)
receipt = {'version':VERSION,'input_sha256':hashlib.sha256(old).hexdigest(),
           'output_sha256':hashlib.sha256(new).hexdigest(),'output':str(output_path),
           'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
           'changed_entries':changed,'required_puzzleslib':'[21.1.58,)',
           'compiled_against_puzzleslib':classpath[1].name,'java_release':21,
           'server_logic_unchanged':True,'integrations_unchanged':['JEI','EMI'],
           'mixins_unchanged':True,'registries_and_resources_unchanged':True}
(BUILD/'receipt.json').write_text(json.dumps(receipt,indent=2))
print(json.dumps(receipt,indent=2))
