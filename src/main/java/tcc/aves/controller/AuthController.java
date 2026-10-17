package tcc.aves.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import tcc.aves.dto.LoginRequest;
import tcc.aves.dto.LoginResponse;
import tcc.aves.dto.UserResponse;
import tcc.aves.exception.InvalidCredentialsException;
import tcc.aves.repository.UserRecord;
import tcc.aves.repository.UserRepository;
import tcc.aves.security.JwtUtil;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        UserRecord user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InvalidCredentialsException("Email ou senha inválidos"));

        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new InvalidCredentialsException("Email ou senha inválidos");
        }

        String token = jwtUtil.generateToken(user.id(), user.email());
        UserResponse userResponse = new UserResponse(
                user.id(),
                user.name(),
                user.email(),
                user.createdAt(),
                user.updatedAt(),
                user.role()
        );
        return ResponseEntity.ok(new LoginResponse(token, userResponse));
    }
}
