package com.loganalysis.credential.interfaces.rest;

import com.loganalysis.credential.application.CredentialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * API 凭证管理
 * 对齐 app.py 中 /api/credentials 系列接口。
 */
@RestController
@RequestMapping("/api/credentials")
public class CredentialController {

    @Autowired
    private CredentialService credentialService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(credentialService.listMasked());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable("id") long id) {
        Map<String, Object> d = credentialService.detail(id);
        if (d == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "凭证不存在");
            return ResponseEntity.status(404).body(err);
        }
        return ResponseEntity.ok(d);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String sid = (String) body.get("secret_id");
        String skey = (String) body.get("secret_key");
        long id = credentialService.create(name, sid, skey);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("message", "创建成功");
        ok.put("id", id);
        return ResponseEntity.status(201).body(ok);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable("id") long id,
                                                      @RequestBody Map<String, Object> body) {
        credentialService.update(id,
                (String) body.get("name"),
                (String) body.get("secret_id"),
                (String) body.get("secret_key"));
        Map<String, Object> ok = new HashMap<>();
        ok.put("message", "更新成功");
        return ResponseEntity.ok(ok);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") long id) {
        credentialService.delete(id);
        Map<String, Object> ok = new HashMap<>();
        ok.put("message", "删除成功");
        return ResponseEntity.ok(ok);
    }
}
