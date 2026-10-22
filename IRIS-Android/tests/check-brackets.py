#!/usr/bin/env python3
import glob
import sys

def check_brackets(path):
    with open(path, 'r', encoding='utf-8', errors='ignore') as f:
        content = f.read()
    stack = []
    in_str = False
    in_char = False
    in_line_comment = False
    in_block_comment = False
    escape = False
    i = 0
    line_num = 1
    col_num = 1
    pairs = {')': '(', '}': '{', ']': '['}
    
    while i < len(content):
        c = content[i]
        if c == '\n':
            line_num += 1
            col_num = 1
            in_line_comment = False
            i += 1
            continue
        col_num += 1
        if in_line_comment:
            i += 1
            continue
        if in_block_comment:
            if c == '*' and i + 1 < len(content) and content[i+1] == '/':
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if in_str:
            if escape:
                escape = False
            elif c == '\\':
                escape = True
            elif c == '"':
                in_str = False
            i += 1
            continue
        if in_char:
            if escape:
                escape = False
            elif c == '\\':
                escape = True
            elif c == "'":
                in_char = False
            i += 1
            continue
        if c == '/' and i + 1 < len(content):
            if content[i+1] == '/':
                in_line_comment = True
                i += 2
                continue
            elif content[i+1] == '*':
                in_block_comment = True
                i += 2
                continue
        if c == '"':
            in_str = True
            i += 1
            continue
        if c == "'":
            in_char = True
            i += 1
            continue
        if c in '({[':
            stack.append((c, line_num, col_num))
        elif c in ')}]':
            if not stack:
                return f'Unmatched closing {c} at {line_num}:{col_num}'
            top, tl, tc = stack.pop()
            if pairs[c] != top:
                return f'Mismatched {top} (line {tl}) with {c} at {line_num}:{col_num}'
        i += 1
    if stack:
        top, tl, tc = stack[-1]
        return f'Unclosed {top} from line {tl}:{tc}'
    return None

def main():
    files = glob.glob('app/src/main/java/**/*.java', recursive=True)
    errors = []
    for f in files:
        err = check_brackets(f)
        if err:
            errors.append(f'{f}: {err}')

    if errors:
        print(f'FAILED with {len(errors)} errors:')
        for e in errors:
            print('  ' + e)
        return 1
    print(f'SUCCESS: Checked {len(files)} Java files, all 100% cleanly bracket balanced.')
    return 0

if __name__ == '__main__':
    sys.exit(main())
