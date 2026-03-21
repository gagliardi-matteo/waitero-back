package com.waitero.back.controller;

import com.waitero.back.dto.SecureTableAccessRequest;
import com.waitero.back.dto.SecureTableAccessResponse;
import com.waitero.back.service.TableAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/table")
@RequiredArgsConstructor
public class TableAccessController {

    private final TableAccessService tableAccessService;

    @PostMapping("/access")
    public ResponseEntity<SecureTableAccessResponse> validateAccess(@RequestBody SecureTableAccessRequest request) {
        return ResponseEntity.ok(tableAccessService.validateAndRegister(request));
    }
}
