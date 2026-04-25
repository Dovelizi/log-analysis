package com.loganalysis.controller;

import com.loganalysis.service.TopicService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Topic 配置管理
 * 对齐 app.py 中 /api/topics 系列接口。
 */
@RestController
@RequestMapping("/api/topics")
public class TopicController {

    @Autowired
    private TopicService topicService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(topicService.listAll());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        topicService.create(body);
        Map<String, Object> ok = new HashMap<>();
        ok.put("message", "创建成功");
        return ResponseEntity.status(201).body(ok);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable("id") long id,
                                                      @RequestBody Map<String, Object> body) {
        topicService.update(id, body);
        Map<String, Object> ok = new HashMap<>();
        ok.put("message", "更新成功");
        return ResponseEntity.ok(ok);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") long id) {
        topicService.delete(id);
        Map<String, Object> ok = new HashMap<>();
        ok.put("message", "删除成功");
        return ResponseEntity.ok(ok);
    }
}
