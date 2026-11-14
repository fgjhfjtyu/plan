#!/usr/bin/env python3
"""Validate the 9,000-record QET Mobile industrial catalogue."""
from pathlib import Path
import json, re, xml.etree.ElementTree as ET, html, subprocess, shutil, sys

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / 'app' / 'src' / 'main' / 'assets'
PARTS = ASSETS / 'parts'
EXPECTED = [1100, 1000, 900, 900, 800, 700, 600, 700, 600, 800, 500, 400]
CATEGORY_NAMES = [
 'کلیدهای محافظ','کنتاکتور و رله','موتورها','سنسورها','شاسی و فرمان','فیوز و سکسیونر',
 'ترانس و منابع تغذیه','درایو و PLC','ترمینال و اتصالات','قطعات الکترونیک','اندازه‌گیری و حفاظت','پنوماتیک و هیدرولیک'
]
ALLOWED = {'line','rect','circle','ellipse','arc','polygon','terminal','text'}

def xml_for(r):
    _, fa, en, cat, sub, maker, ref, rating, voltage, poles, symbol, term_count, spec = r
    st='line-style:normal;line-weight:thin;filling:none;color:black'
    esc=lambda x: html.escape(str(x), quote=True)
    lines=[]
    if symbol in ('motor','sensor','meter','valve'):
        mark={'motor':'M','meter':'M','valve':'V'}.get(symbol,'S')
        lines += [f'<circle x="18" y="18" diameter="24" style="{st}"/>',
                  f'<text x="25" y="34" text="{mark}" size="10" style="{st}"/>']
    elif symbol=='electronic':
        lines += [f'<rect x="16" y="18" width="28" height="24" style="{st}"/>',
                  f'<line x1="20" y1="30" x2="40" y2="30" style="{st}"/>']
    elif symbol=='transformer':
        lines += [f'<circle x="20" y="20" diameter="18" style="{st}"/>',
                  f'<circle x="22" y="22" diameter="18" style="{st}"/>']
    else:
        lines += [f'<rect x="18" y="18" width="24" height="24" style="{st}"/>',
                  f'<line x1="22" y1="30" x2="38" y2="30" style="{st}"/>']
    n=max(2,min(12,int(term_count or 2)))
    for i in range(n):
        left=i<n/2
        idx=i if left else i-(n+1)//2
        side_count=(n+1)//2 if left else n//2
        y=round(8+(idx+1)*(44/(side_count+1)))
        x=0 if left else 60
        lines.append(f'<terminal x="{x}" y="{y}" orientation="{0 if left else 2}"/>')
        lines.append(f'<line x1="{x}" y1="{y}" x2="{18 if left else 42}" y2="{y}" style="{st}"/>')
    return (f'<definition type="element" version="0.3" width="60" height="60" hotspot_x="30" hotspot_y="30">'
            f'<names><name lang="en">{esc(en)}</name><name lang="fa">{esc(fa)}</name></names>'
            f'<description>{"".join(lines)}</description></definition>')

def fail(errors, msg):
    if len(errors)<100: errors.append(msg)

errors=[]; all_rows=[]; counts=[]; ids=set(); refs=set(); total_bytes=0
for ci, expected in enumerate(EXPECTED,1):
    path=PARTS/f'cat_{ci:02d}.js'
    if not path.is_file():
        fail(errors,f'missing category file: {path.name}'); counts.append(0); continue
    raw=path.read_text(encoding='utf-8'); total_bytes += path.stat().st_size
    match=re.fullmatch(r"window\.QET_PARTS=window\.QET_PARTS\|\|\{\};window\.QET_PARTS\['(\d{2})'\]=(.*);",raw,re.S)
    if not match or match.group(1)!=f'{ci:02d}':
        fail(errors,f'invalid JavaScript data wrapper: {path.name}'); counts.append(0); continue
    try: rows=json.loads(match.group(2))
    except Exception as e:
        fail(errors,f'JSON parse {path.name}: {e}'); counts.append(0); continue
    counts.append(len(rows))
    if len(rows)!=expected: fail(errors,f'{path.name}: {len(rows)} != {expected}')
    for pos,r in enumerate(rows,1):
        if not isinstance(r,list) or len(r)!=13:
            fail(errors,f'{path.name} row {pos}: expected 13 fields'); continue
        rid,fa,en,cat,sub,maker,ref,rating,voltage,poles,symbol,n,spec=r
        if not all(str(x).strip() for x in (rid,fa,en,cat,sub,maker,ref,symbol,spec)):
            fail(errors,f'empty required field: {rid}')
        if cat!=CATEGORY_NAMES[ci-1]: fail(errors,f'wrong category for {rid}: {cat}')
        if rid in ids: fail(errors,f'duplicate id: {rid}')
        ids.add(rid)
        pair=(maker,ref)
        if pair in refs: fail(errors,f'duplicate manufacturer/reference: {pair}')
        refs.add(pair)
        if not str(ref).startswith('GEN-'):
            fail(errors,f'reference lacks generated-code prefix: {rid}')
        try:
            root=ET.fromstring(xml_for(r))
            if root.tag!='definition': fail(errors,f'wrong XML root: {rid}')
            desc=root.find('description')
            if desc is None: fail(errors,f'missing description: {rid}'); continue
            if any(ch.tag not in ALLOWED for ch in list(desc)):
                fail(errors,f'unsupported primitive: {rid}')
            terms=[x for x in list(desc) if x.tag=='terminal']
            shapes=[x for x in list(desc) if x.tag in ALLOWED-{'terminal'}]
            if not terms: fail(errors,f'no terminal: {rid}')
            if not shapes: fail(errors,f'no shape: {rid}')
            width=float(root.attrib['width']); height=float(root.attrib['height'])
            for t in terms:
                x=float(t.attrib['x']); y=float(t.attrib['y']); o=int(t.attrib['orientation'])
                if not (0<=x<=width and 0<=y<=height): fail(errors,f'terminal out of bounds: {rid}')
                if o not in (0,1,2,3): fail(errors,f'invalid terminal orientation: {rid}')
        except Exception as e: fail(errors,f'XML parse {rid}: {e}')
        all_rows.append(r)

index=(ASSETS/'index.html').read_text(encoding='utf-8') if (ASSETS/'index.html').is_file() else ''
required=['id="tabI"','id="icat"','id="isubcat"','function loadIndustrialCategory','function industrialXml','function addIndustrialRecord','addElement(key)','parts/cat_']
for token in required:
    if token not in index: fail(errors,f'index.html missing integration token: {token}')

print('Category counts:',counts)
print('Total records:',len(all_rows))
print('Unique IDs:',len(ids))
print('Unique manufacturer/reference pairs:',len(refs))
print('XML definitions parsed:',len(all_rows))
print('Parts directory size: %.2f MB'%(total_bytes/1024/1024))
print('HTML integration markers:',sum(1 for token in required if token in index),'/',len(required))
node=shutil.which('node')
if node:
    # Syntax-check each category data file and the inline application script.
    for f in sorted(PARTS.glob('cat_*.js')):
        p=subprocess.run([node,'--check',str(f)],capture_output=True,text=True)
        if p.returncode: fail(errors,f'JavaScript syntax: {f.name}: {p.stderr[:300]}')
    scripts=re.findall(r'<script\b[^>]*>(.*?)</script>',index,re.S|re.I)
    for i,script in enumerate(scripts):
        tmp=ROOT/f'.validate-inline-{i}.js'; tmp.write_text(script,encoding='utf-8')
        p=subprocess.run([node,'--check',str(tmp)],capture_output=True,text=True)
        tmp.unlink(missing_ok=True)
        if p.returncode: fail(errors,f'inline JS syntax: {p.stderr[:300]}')
    print('JavaScript syntax checks: passed' if not errors else 'JavaScript syntax checks: errors found')
else:
    print('JavaScript syntax checks: skipped (Node.js not installed)')

if errors:
    print('VALIDATION FAILED:',len(errors),'error(s)')
    print('\n'.join(errors[:30]))
    sys.exit(1)
if len(all_rows)!=9000 or len(ids)!=9000 or len(refs)!=9000 or counts!=EXPECTED:
    print('VALIDATION FAILED: expected exactly 9000 records and category totals')
    sys.exit(1)
print('VALIDATION PASSED: exactly 9000 parts')
