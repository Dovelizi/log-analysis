# -*- coding: utf-8 -*-
"""
报表截图服务
使用 Playwright 截取报表页面，生成与前端手动推送完全一致的图片
"""
import base64
import asyncio
from datetime import datetime
from io import BytesIO

PLAYWRIGHT_AVAILABLE = False
PIL_AVAILABLE = False

try:
    from playwright.async_api import async_playwright
    PLAYWRIGHT_AVAILABLE = True
except ImportError:
    print("[ScreenshotService] playwright 未安装，截图功能不可用")

try:
    from PIL import Image
    PIL_AVAILABLE = True
except ImportError:
    print("[ScreenshotService] PIL 未安装，将无法添加边距")


async def capture_report_screenshot(base_url: str, date: str = None) -> str:
    """
    使用 Playwright 截取报表页面
    与前端 html2canvas 截图保持完全一致：
    - scale: 2 (通过设备像素比实现)
    - 背景色: #0f1419
    - 添加 50px 边距 (25px * scale 2)
    - 隐藏 .report-export-hide 元素
    - 显示 .report-export-title 标题
    
    Args:
        base_url: 服务基础 URL，如 http://127.0.0.1:8080
        date: 报表日期，格式 YYYY-MM-DD，默认为今天
    
    Returns:
        base64 编码的图片字符串，失败返回 None
    """
    if not PLAYWRIGHT_AVAILABLE:
        print("[ScreenshotService] Playwright 不可用")
        return None
    
    if not date:
        date = datetime.now().strftime('%Y-%m-%d')
    
    try:
        async with async_playwright() as p:
            # 启动浏览器
            browser = await p.chromium.launch(
                headless=True,
                args=['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu']
            )
            
            # 创建页面，设置较大的视口以确保完整渲染
            # 使用 device_scale_factor=2 与前端 html2canvas scale: 2 一致
            page = await browser.new_page(
                viewport={'width': 1400, 'height': 3000},
                device_scale_factor=2  # 与前端 html2canvas scale: 2 一致
            )
            
            # 访问主页面，带上日期参数（如果前端支持的话）
            # 直接访问报表页面并等待加载
            print(f"[ScreenshotService] 访问主页面: {base_url}")
            await page.goto(base_url, wait_until='networkidle')
            
            # 等待 Vue 应用初始化
            await page.wait_for_timeout(2000)
            
            # 点击"报表详情"导航切换到报表页面
            print(f"[ScreenshotService] 点击报表详情导航...")
            try:
                await page.click('text=报表详情')
            except Exception as e:
                print(f"[ScreenshotService] 点击导航失败: {e}")
            
            # 等待报表容器出现
            print(f"[ScreenshotService] 等待报表容器...")
            try:
                await page.wait_for_selector('#reportContainer', timeout=10000)
            except Exception as e:
                print(f"[ScreenshotService] 等待报表容器超时: {e}")
            
            # 等待初始数据加载完成（点击导航会自动触发 loadReportData）
            print(f"[ScreenshotService] 等待初始数据加载...")
            await page.wait_for_load_state('networkidle')
            await page.wait_for_timeout(3000)
            
            # 检查当前选中的日期
            current_date = await page.evaluate('''() => {
                const select = document.querySelector('#reportContainer select');
                return select ? select.value : null;
            }''')
            print(f"[ScreenshotService] 当前选中日期: {current_date}, 目标日期: {date}")
            
            # 如果日期不同，需要重新选择并等待数据加载
            if current_date != date:
                print(f"[ScreenshotService] 切换日期到: {date}")
                try:
                    # 选择日期
                    date_select = await page.query_selector('#reportContainer select')
                    if date_select:
                        await date_select.select_option(value=date)
                        print(f"[ScreenshotService] 已选择日期: {date}")
                        
                        # 等待网络请求完成
                        await page.wait_for_load_state('networkidle')
                        
                        # 等待加载指示器消失
                        try:
                            await page.wait_for_function('''() => {
                                const loadingDiv = document.querySelector('#reportContainer .animate-spin');
                                return !loadingDiv;
                            }''', timeout=30000)
                        except Exception as e:
                            print(f"[ScreenshotService] 等待加载完成超时: {e}")
                        
                        # 等待图表重新渲染
                        await page.wait_for_timeout(5000)
                except Exception as e:
                    print(f"[ScreenshotService] 设置日期失败: {e}")
            else:
                # 日期相同，只需等待当前数据加载完成
                print(f"[ScreenshotService] 日期相同，等待数据加载完成...")
                try:
                    await page.wait_for_function('''() => {
                        const loadingDiv = document.querySelector('#reportContainer .animate-spin');
                        return !loadingDiv;
                    }''', timeout=30000)
                except Exception as e:
                    print(f"[ScreenshotService] 等待加载完成超时: {e}")
            
            # 等待图表渲染完成
            print(f"[ScreenshotService] 等待图表渲染...")
            await page.wait_for_timeout(3000)
            
            # 等待所有 canvas 元素渲染完成（至少 4 个图表）
            try:
                await page.wait_for_function('''() => {
                    const canvases = document.querySelectorAll('#reportContainer canvas');
                    if (canvases.length < 4) return false;
                    
                    // 检查每个 canvas 是否有内容（宽高大于 0）
                    for (let canvas of canvases) {
                        if (canvas.width === 0 || canvas.height === 0) return false;
                    }
                    return true;
                }''', timeout=15000)
                canvas_count = await page.evaluate('document.querySelectorAll("#reportContainer canvas").length')
                print(f"[ScreenshotService] 检测到 {canvas_count} 个图表 canvas")
            except Exception as e:
                print(f"[ScreenshotService] 等待图表 canvas 超时: {e}")
            
            # 再等待确保图表数据完全渲染
            await page.wait_for_timeout(3000)
            
            # 验证图表数据是否正确加载（检查趋势图是否有足够的数据点）
            chart_data_ok = await page.evaluate('''() => {
                // 检查第一个 canvas（错误趋势图）是否有内容
                const canvases = document.querySelectorAll('#reportContainer canvas');
                if (canvases.length < 4) return false;
                
                // 检查 canvas 是否有实际绑定的 echarts 实例
                for (let canvas of canvases) {
                    const chart = echarts.getInstanceByDom(canvas.parentElement);
                    if (chart) {
                        const option = chart.getOption();
                        // 检查是否有数据
                        if (option && option.series && option.series.length > 0) {
                            const series = option.series[0];
                            if (series.data && series.data.length > 0) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }''')
            print(f"[ScreenshotService] 图表数据检查: {'正常' if chart_data_ok else '可能为空'}")
            
            # 如果图表数据可能有问题，再等待一下
            if not chart_data_ok:
                print(f"[ScreenshotService] 图表数据可能未加载完成，额外等待 5 秒...")
                await page.wait_for_timeout(5000)
            
            # 查找报表容器
            container = await page.query_selector('#reportContainer')
            if not container:
                print("[ScreenshotService] 未找到报表容器 #reportContainer")
                await browser.close()
                return None
            
            # 隐藏不需要导出的元素，显示标题（与前端 triggerPush 逻辑完全一致）
            print(f"[ScreenshotService] 准备截图（隐藏工具栏，显示标题）...")
            await page.evaluate('''() => {
                const container = document.getElementById('reportContainer');
                if (!container) return;
                
                // 隐藏导出按钮等（.report-export-hide 类）
                const hideElements = container.querySelectorAll('.report-export-hide');
                hideElements.forEach(el => el.style.display = 'none');
                
                // 显示标题（.report-export-title 类）
                const titleElement = container.querySelector('.report-export-title');
                if (titleElement) titleElement.style.display = 'block';
                
                // 修复 tag 元素样式（与前端 triggerPush 完全一致）
                const tagElements = container.querySelectorAll('.tag');
                tagElements.forEach(el => {
                    el.style.display = 'inline-block';
                    el.style.lineHeight = '1';
                    el.style.paddingTop = '0.15rem';
                    el.style.paddingBottom = '0.35rem';
                });
            }''')
            
            # 等待样式生效（与前端一致，等待 150ms）
            await page.wait_for_timeout(150)
            
            # 获取容器的完整高度，确保截取完整内容
            box = await container.bounding_box()
            if box:
                print(f"[ScreenshotService] 容器尺寸: {box['width']}x{box['height']}")
            
            # 截图（由于 device_scale_factor=2，截图已经是 2 倍分辨率）
            screenshot_bytes = await container.screenshot(
                type='png',
                omit_background=False
            )
            
            # 关闭浏览器
            await browser.close()
            
            # 添加边距（与前端 html2canvas 一致：padding = 50px，即 25px * scale 2）
            if PIL_AVAILABLE:
                try:
                    # 打开原始截图
                    original_image = Image.open(BytesIO(screenshot_bytes))
                    original_width, original_height = original_image.size
                    
                    # 边距大小（与前端一致：50px）
                    padding = 50
                    
                    # 创建带边距的新图片，背景色 #0f1419
                    new_width = original_width + padding * 2
                    new_height = original_height + padding * 2
                    new_image = Image.new('RGB', (new_width, new_height), (15, 20, 25))  # #0f1419
                    
                    # 将原始截图粘贴到中间
                    new_image.paste(original_image, (padding, padding))
                    
                    # 保存为 PNG
                    output = BytesIO()
                    new_image.save(output, format='PNG')
                    screenshot_bytes = output.getvalue()
                    
                    print(f"[ScreenshotService] 已添加 {padding}px 边距，新尺寸: {new_width}x{new_height}")
                except Exception as e:
                    print(f"[ScreenshotService] 添加边距失败: {e}，使用原始截图")
            else:
                print("[ScreenshotService] PIL 不可用，跳过添加边距")
            
            # 转换为 base64
            image_base64 = base64.b64encode(screenshot_bytes).decode('utf-8')
            print(f"[ScreenshotService] 截图成功，大小: {len(image_base64)} bytes")
            
            return image_base64
            
    except Exception as e:
        print(f"[ScreenshotService] 截图失败: {e}")
        import traceback
        traceback.print_exc()
        return None


def capture_report_screenshot_sync(base_url: str, date: str = None) -> str:
    """
    同步版本的截图函数
    """
    try:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        result = loop.run_until_complete(capture_report_screenshot(base_url, date))
        loop.close()
        return result
    except Exception as e:
        print(f"[ScreenshotService] 同步截图失败: {e}")
        return None


if __name__ == '__main__':
    # 测试
    import sys
    base_url = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8080'
    date = sys.argv[2] if len(sys.argv) > 2 else None
    
    result = capture_report_screenshot_sync(base_url, date)
    if result:
        print(f"截图成功，base64 长度: {len(result)}")
        # 保存到文件
        with open('/tmp/report_screenshot.png', 'wb') as f:
            f.write(base64.b64decode(result))
        print("已保存到 /tmp/report_screenshot.png")
    else:
        print("截图失败")
