import os

root = os.getcwd()
targets = []
for base, dirs, files in os.walk(root):
    norm = base.replace('\\', '/')
    if '/build' in norm or norm.endswith('/build'):
        dirs[:] = []
        continue
    for f in files:
        if f.endswith('.kt') or f in (
            'build.gradle.kts', 'proguard-rules.pro',
            'settings.gradle.kts', 'gradle.properties'
        ):
            targets.append(os.path.join(base, f))

readme = os.path.join(root, 'README.md')
if os.path.exists(readme):
    targets.append(readme)

old = 'com.openreader'
new = 'com.qingsongreader'
count = 0
for p in targets:
    try:
        with open(p, 'r', encoding='utf-8') as fh:
            data = fh.read()
    except Exception:
        continue
    if old in data:
        with open(p, 'w', encoding='utf-8') as fh:
            fh.write(data.replace(old, new))
        count += 1
        print('UPDATED', os.path.relpath(p, root))

print('TOTAL FILES UPDATED:', count)
