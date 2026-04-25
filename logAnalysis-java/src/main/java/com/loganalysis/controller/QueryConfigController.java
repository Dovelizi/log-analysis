package com.loganalysis.controller;

import com.loganalysis.service.QueryConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * query_configs CRUD
 * 对齐 app.py 中 /api/query-configs 系列接口。
 */
@RestController
@RequestMapping("/api/query-configs")
public class QueryConfigController {

    @Autowired
    private QueryConfigService queryConfigService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(queryConfigService.listAll());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        queryConfigService.create(body);
        Map<String, Object> ok = new HashMap<>();
        ok.put("message", "创建成功");
        return ResponseEntity.status(201).body(ok);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable("id") long id,
                                                      @RequestBody Map<String, Object> body) {
        queryConfigService.update(id, body);
        Map<String, Object> ok = new HashMap<>();
        ok.put("message", "更新成功");
        return ResponseEntity.ok(ok);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") long id) {
        queryConfigService.delete(id);
        Map<String, Object> ok = new HashMap<>();
        ok.put("message", "删除成功");
        return ResponseEntity.ok(ok);
    }
}
