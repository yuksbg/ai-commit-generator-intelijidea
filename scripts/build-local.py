#!/usr/bin/env python3
"""Compile, test and package using an installed IDE, without network downloads."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--ide', default='/Applications/GoLand.app/Contents')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
version = subprocess.check_output(
    [sys.executable, str(root / 'scripts/version.py')], text=True).strip()
ide = Path(args.ide).resolve()
if (ide / 'Contents').is_dir():
    ide = ide / 'Contents'
java_home = ide / 'jbr/Contents/Home'
if not java_home.exists():
    java_home = ide / 'jbr'
if not (java_home / 'bin/javac').exists():
    java_home = Path(os.environ.get('JAVA_HOME', ''))
if not (java_home / 'bin/javac').exists():
    raise SystemExit('Set JAVA_HOME to a JDK 25 installation.')
build = root / 'build/local'
if build.exists():
    shutil.rmtree(build)
classes = build / 'classes'
classes.mkdir(parents=True)
jars = list((ide / 'lib').glob('*.jar')) + list((ide / 'plugins/vcs-git/lib').glob('*.jar')) + list((ide / 'plugins/git4idea/lib').glob('*.jar'))
classpath = os.pathsep.join(map(str, jars))
sources = sorted((root / 'src/main/java').rglob('*.java')) + sorted((root / 'src/test/java').rglob('*.java'))
subprocess.run([str(java_home / 'bin/javac'), '-encoding', 'UTF-8', '--release', '25', '-classpath', classpath,
                '-d', str(classes), *map(str, sources)], check=True)
subprocess.run([str(java_home / 'bin/java'), '-ea', '-cp', str(classes) + os.pathsep + classpath,
                'dev.aicommit.CoreTest'], check=True)
jar = build / 'ai-commit-message.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as out:
    for file in classes.rglob('*.class'):
        if not file.name.startswith('CoreTest'):
            out.write(file, file.relative_to(classes))
    resources = root / 'src/main/resources'
    for file in resources.rglob('*'):
        if file.is_file():
            if file.relative_to(resources).as_posix() == 'META-INF/plugin.xml':
                descriptor = file.read_text().replace('<idea-plugin>', f'<idea-plugin>\n  <version>{version}</version>', 1)
                out.writestr('META-INF/plugin.xml', descriptor)
            else:
                out.write(file, file.relative_to(resources))
archive = root / f'build/distributions/ai-commit-message-{version}.zip'
archive.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as out:
    out.write(jar, 'ai-commit-message/lib/ai-commit-message.jar')
print(f'Built {archive}')
