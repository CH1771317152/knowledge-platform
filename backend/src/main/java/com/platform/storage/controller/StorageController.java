package com.platform.storage.controller;

import com.platform.auth.application.CurrentUserService;
import com.platform.auth.infrastructure.security.AuthenticatedPrincipal;
import com.platform.common.response.ApiResponse;
import com.platform.storage.application.StoragePresignService;
import com.platform.storage.domain.PresignedUpload;
import com.platform.storage.dto.PresignRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/storage")
@Profile("!test")
public class StorageController {

    private final StoragePresignService storagePresignService;
    private final CurrentUserService currentUserService;

    public StorageController(StoragePresignService storagePresignService,
                             CurrentUserService currentUserService) {
        this.storagePresignService = storagePresignService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/presign")
    public ApiResponse<PresignedUpload> presign(@Valid @RequestBody PresignRequest request) {
        AuthenticatedPrincipal principal = currentUserService.requirePrincipal();
        return ApiResponse.ok(storagePresignService.presignForUser(principal.userId(), request));
    }
}
