#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
修复重复的 triggerPushByConfig 函数定义
"""
import re

def fix_duplicate_functions():
    """修复重复的函数定义"""
    
    # 读取文件
    with open('static/index.html', 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 查找第一个 triggerPushByConfig 函数的位置
    first_match = re.search(r'// 根据配置推送 - 按照配置的推送模式决定推送日期\s*\n\s*const triggerPushByConfig = async \(config\) => \{', content)
    
    if not first_match:
        print("未找到第一个 triggerPushByConfig 函数")
        return
    
    first_start = first_match.start()
    
    # 找到第一个函数的结束位置
    brace_count = 0
    in_function = False
    first_end = first_start
    
    for i in range(first_start, len(content)):
        char = content[i]
        if char == '{':
            if not in_function and 'triggerPushByConfig' in content[max(0, i-200):i]:
                in_function = True
            if in_function:
                brace_count += 1
        elif char == '}':
            if in_function:
                brace_count -= 1
                if brace_count == 0:
                    first_end = i + 1
                    break
    
    # 保留第一个函数定义
    first_function = content[first_start:first_end]
    
    # 删除所有其他的 triggerPushByConfig 函数定义
    pattern = r'// 根据配置推送 - 按照配置的推送模式决定推送日期\s*\n\s*const triggerPushByConfig = async \(config\) => \{[^}]*\{[^}]*\}[^}]*\}[^}]*\};'
    
    # 更简单的方法：删除所有 const triggerPushByConfig 开头的函数
    pattern = r'const triggerPushByConfig = async \(config\) => \{(?:[^{}]|\{[^{}]*\})*\};'
    
    # 找到所有匹配
    matches = list(re.finditer(pattern, content, re.DOTALL))
    
    print(f"找到 {len(matches)} 个 triggerPushByConfig 函数定义")
    
    # 从后往前删除（避免位置偏移）
    new_content = content
    for match in reversed(matches[1:]):  # 保留第一个，删除其他的
        new_content = new_content[:match.start()] + new_content[match.end():]
        print(f"删除位置 {match.start()}-{match.end()} 的重复函数")
    
    # 写回文件
    with open('static/index.html', 'w', encoding='utf-8') as f:
        f.write(new_content)
    
    print("修复完成")

if __name__ == '__main__':
    fix_duplicate_functions()