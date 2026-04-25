package com.loganalysis.dashboard.interfaces.rest;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前端静态页面路由。
 *
 * 对齐 Python 原实现（app.py 1804-1814 行）:
 *   / → static/index.html
 *   /table-mapping → 302 redirect /#table-mappings
 *
 * Spring Boot 会自动把 src/main/resources/static/* 暴露成 /* 静态资源，
 * 所以 / → index.html 其实已经由 Spring 默认行为覆盖；
 * 这里只需要显式处理 /table-mapping 的重定向。
 */
@Controller
public class PageController {

    @GetMapping("/table-mapping")
    public String tableMappingRedirect() {
        return "redirect:/#table-mappings";
    }
}
