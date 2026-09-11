package tcc.aves.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tcc.aves.dto.UserRequest;
import tcc.aves.dto.UserResponse;
import tcc.aves.dto.UserUpdateRequest;
import tcc.aves.service.UserService;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request) {
        UserResponse response = userService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Cada conta só pode consultar o próprio perfil (ver {@code AuthenticatedUser}). */
    @PreAuthorize("#id == principal.id")
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> findById(@PathVariable String id) {
        return ResponseEntity.ok(userService.findById(id));
    }

    @PreAuthorize("#id == principal.id")
    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> update(@PathVariable String id,
                                                @Valid @RequestBody UserUpdateRequest request) {
        return ResponseEntity.ok(userService.update(id, request));
    }

    @PreAuthorize("#id == principal.id")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
