# -*- coding: utf-8 -*-
"""
定时查询调度器
用于执行启用了定时查询的查询配置和定时推送的报表配置
通过调用本地 /api/search-logs 和 /api/report/push 接口执行
"""
import os
import sys
import time
import threading
import requests
from datetime import datetime, timedelta

# 添加项目根目录到路径
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from config.database import (
    get_db_connection, dict_cursor, get_db_type
)


class ScheduledQueryExecutor:
    """定时查询执行器"""
    
    def __init__(self, base_url='http://127.0.0.1:8080'):
        self.running = False
        self.thread = None
        self.last_execution = {}  # 记录每个配置的上次执行时间
        self.last_push_execution = {}  # 记录每个推送配置的上次执行时间
        self.base_url = base_url
        self._initialized_configs = set()  # 记录已初始化的配置，避免启动时立即执行
        self._initialized_push_configs = set()  # 记录已初始化的推送配置
        
    def start(self):
        """启动调度器"""
        if self.running:
            print("[Scheduler] 调度器已在运行")
            return
        
        self.running = True
        self.thread = threading.Thread(target=self._run_loop, daemon=True)
        self.thread.start()
        print("[Scheduler] 定时查询调度器已启动")
        
    def stop(self):
        """停止调度器"""
        self.running = False
        if self.thread:
            self.thread.join(timeout=5)
        print("[Scheduler] 定时查询调度器已停止")
        
    def _run_loop(self):
        """主循环"""
        while self.running:
            try:
                self._check_and_execute()
                self._check_and_execute_push()
            except Exception as e:
                print(f"[Scheduler] 执行出错: {e}")
            
            # 每10秒检查一次
            time.sleep(10)
    
    def _check_and_execute(self):
        """检查并执行需要运行的查询"""
        db = get_db_connection()
        cursor = dict_cursor(db)
        
        try:
            # 获取所有启用定时查询的配置
            cursor.execute('''
                SELECT id, name, schedule_interval
                FROM query_configs
                WHERE schedule_enabled = 1
            ''')
            
            configs = cursor.fetchall()
            
            now = time.time()
            
            for config in configs:
                config = dict(config) if not isinstance(config, dict) else config
                config_id = config['id']
                interval = config.get('schedule_interval', 300)
                
                # 首次遇到该配置时，初始化上次执行时间为当前时间
                # 这样可以避免服务启动时立即执行所有定时任务
                if config_id not in self._initialized_configs:
                    self._initialized_configs.add(config_id)
                    # 如果没有记录过执行时间，设置为当前时间，等待下一个周期再执行
                    if config_id not in self.last_execution:
                        self.last_execution[config_id] = now
                        print(f"[Scheduler] 初始化配置 {config['name']} (ID: {config_id}), 将在 {interval} 秒后首次执行")
                        continue
                
                # 检查是否需要执行
                last_exec = self.last_execution.get(config_id, now)
                if now - last_exec >= interval:
                    print(f"[Scheduler] 执行定时查询: {config['name']} (ID: {config_id})")
                    self._execute_query(config_id, config['name'])
                    self.last_execution[config_id] = now
                    
        except Exception as e:
            print(f"[Scheduler] 检查配置出错: {e}")
        finally:
            db.close()
    
    def _execute_query(self, config_id, config_name):
        """通过调用 /api/search-logs 接口执行查询"""
        try:
            url = f"{self.base_url}/api/search-logs"
            payload = {"config_id": config_id}
            
            response = requests.post(url, json=payload, timeout=60)
            
            if response.status_code == 200:
                result = response.json()
                # 检查结果
                if 'Response' in result:
                    results_count = len(result.get('Response', {}).get('Results', []))
                    process_result = result.get('_process_result', {})
                    success_count = process_result.get('success_count', 0)
                    print(f"[Scheduler] 查询 {config_name} 完成: 返回 {results_count} 条, 处理成功 {success_count} 条")
                else:
                    print(f"[Scheduler] 查询 {config_name} 完成")
            else:
                print(f"[Scheduler] 查询 {config_name} 失败: HTTP {response.status_code}")
                
        except requests.exceptions.Timeout:
            print(f"[Scheduler] 查询 {config_name} 超时")
        except requests.exceptions.ConnectionError:
            print(f"[Scheduler] 查询 {config_name} 连接失败，服务可能未启动")
        except Exception as e:
            print(f"[Scheduler] 执行查询 {config_name} 出错: {e}")

    def _check_and_execute_push(self):
        """检查并执行需要运行的推送任务"""
        db = get_db_connection()
        cursor = dict_cursor(db)
        db_type = get_db_type()
        
        try:
            # 获取所有启用定时推送的配置
            cursor.execute('''
                SELECT id, name, schedule_time, last_push_time, last_scheduled_push_time, push_mode, push_date, relative_days
                FROM report_push_config
                WHERE schedule_enabled = 1
            ''')
            
            configs = cursor.fetchall()
            now = datetime.now()
            current_time = now.strftime('%H:%M')
            today_str = now.strftime('%Y-%m-%d')
            
            for config in configs:
                config = dict(config) if not isinstance(config, dict) else config
                config_id = config['id']
                config_name = config['name']
                schedule_time = config.get('schedule_time')  # 格式: HH:MM
                last_push_time = config.get('last_push_time')
                last_scheduled_push_time = config.get('last_scheduled_push_time')  # 最后定时推送时间
                push_mode = config.get('push_mode', 'daily')
                push_date = config.get('push_date')
                relative_days = config.get('relative_days', 0)
                
                if not schedule_time:
                    continue
                
                # 首次遇到该配置时，初始化
                if config_id not in self._initialized_push_configs:
                    self._initialized_push_configs.add(config_id)
                    print(f"[Scheduler] 初始化推送配置 {config_name} (ID: {config_id}), 推送模式: {push_mode}, 定时推送时间: {schedule_time}")
                
                # 检查是否到达推送时间 (使用时间范围匹配，避免错过精确时间点)
                schedule_hour, schedule_minute = map(int, schedule_time.split(':'))
                current_hour = now.hour
                current_minute = now.minute
                
                # 检查是否在推送时间的1分钟内
                should_push = False
                if current_hour == schedule_hour and abs(current_minute - schedule_minute) <= 1:
                    should_push = True
                
                if should_push:
                    # 根据推送模式确定推送日期
                    push_target_date = None
                    
                    if push_mode == 'daily':
                        # 每日定时推送，使用当天日期
                        push_target_date = today_str
                    elif push_mode == 'date':
                        # 指定日期推送，只在指定日期推送
                        if push_date:
                            push_date_str = str(push_date) if hasattr(push_date, 'strftime') else str(push_date)
                            if push_date_str[:10] == today_str:
                                push_target_date = today_str
                    elif push_mode == 'relative':
                        # 相对日期推送，推送T-N天的数据
                        target_date = now - timedelta(days=relative_days)
                        push_target_date = target_date.strftime('%Y-%m-%d')
                    
                    if not push_target_date:
                        continue
                    
                    # 检查今天是否已经定时推送过（使用 last_scheduled_push_time 而不是 last_push_time）
                    already_pushed_today = False
                    if last_scheduled_push_time:
                        if hasattr(last_scheduled_push_time, 'strftime'):
                            last_scheduled_date = last_scheduled_push_time.strftime('%Y-%m-%d')
                        else:
                            last_scheduled_date = str(last_scheduled_push_time)[:10]
                        if last_scheduled_date == today_str:
                            already_pushed_today = True
                    
                    # 检查内存中是否已执行过
                    last_exec = self.last_push_execution.get(config_id)
                    if last_exec and last_exec == today_str:
                        already_pushed_today = True
                    
                    if not already_pushed_today:
                        print(f"[Scheduler] 执行定时推送: {config_name} (ID: {config_id}), 推送模式: {push_mode}, 目标日期: {push_target_date}")
                        self._execute_push(config_id, config_name, push_target_date)
                        self.last_push_execution[config_id] = today_str
                        
                        # 更新数据库中的 last_push_time 和 last_scheduled_push_time
                        try:
                            if db_type == 'mysql':
                                cursor.execute('''
                                    UPDATE report_push_config 
                                    SET last_push_time = NOW(), last_scheduled_push_time = NOW() 
                                    WHERE id = %s
                                ''', (config_id,))
                            else:
                                cursor.execute('''
                                    UPDATE report_push_config 
                                    SET last_push_time = CURRENT_TIMESTAMP, last_scheduled_push_time = CURRENT_TIMESTAMP 
                                    WHERE id = ?
                                ''', (config_id,))
                            db.commit()
                        except Exception as e:
                            print(f"[Scheduler] 更新推送时间失败: {e}")
                    
        except Exception as e:
            print(f"[Scheduler] 检查推送配置出错: {e}")
        finally:
            db.close()
    
    def _execute_push(self, config_id, config_name, target_date=None):
        """通过调用 /api/report/push 接口执行推送，使用 /api/report/screenshot 生成图片"""
        try:
            # 使用指定日期或当天日期作为报表日期
            if target_date:
                report_date = target_date
            else:
                report_date = datetime.now().strftime('%Y-%m-%d')
            
            image_base64 = None
            
            # 调用 /api/report/screenshot API 获取截图（与前端手动推送使用相同的 Playwright 截图）
            try:
                screenshot_url = f"{self.base_url}/api/report/screenshot?date={report_date}"
                print(f"[Scheduler] 调用截图 API: {screenshot_url}")
                screenshot_response = requests.get(screenshot_url, timeout=120)
                
                if screenshot_response.status_code == 200:
                    result = screenshot_response.json()
                    if result.get('success') and result.get('image_base64'):
                        image_base64 = result['image_base64']
                        print(f"[Scheduler] 截图 API 成功，大小: {len(image_base64)} bytes")
                    else:
                        print(f"[Scheduler] 截图 API 返回失败: {result.get('error', '未知错误')}")
                else:
                    print(f"[Scheduler] 截图 API 请求失败: HTTP {screenshot_response.status_code}")
            except requests.exceptions.Timeout:
                print(f"[Scheduler] 截图 API 超时")
            except Exception as e:
                print(f"[Scheduler] 截图 API 异常: {e}")
            
            # 调用推送接口
            url = f"{self.base_url}/api/report/push"
            payload = {
                "config_id": config_id,
                "date": report_date
            }
            
            # 如果有图片，添加到请求中
            if image_base64:
                payload["image_base64"] = image_base64
            
            response = requests.post(url, json=payload, timeout=120)
            
            if response.status_code == 200:
                result = response.json()
                print(f"[Scheduler] 推送 {config_name} 成功: {result.get('message', 'OK')}")
            else:
                error_msg = response.json().get('error', response.text) if response.text else f'HTTP {response.status_code}'
                print(f"[Scheduler] 推送 {config_name} 失败: {error_msg}")
                
        except requests.exceptions.Timeout:
            print(f"[Scheduler] 推送 {config_name} 超时")
        except requests.exceptions.ConnectionError:
            print(f"[Scheduler] 推送 {config_name} 连接失败，服务可能未启动")
        except Exception as e:
            print(f"[Scheduler] 执行推送 {config_name} 出错: {e}")


# 全局调度器实例
scheduler = ScheduledQueryExecutor()


def start_scheduler():
    """启动调度器"""
    scheduler.start()


def stop_scheduler():
    """停止调度器"""
    scheduler.stop()


if __name__ == '__main__':
    # 测试运行
    print("启动定时查询调度器...")
    start_scheduler()
    
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        stop_scheduler()
        print("调度器已停止")
