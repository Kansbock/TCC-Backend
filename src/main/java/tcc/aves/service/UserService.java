package tcc.aves.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import tcc.aves.dto.UserRequest;
import tcc.aves.dto.UserResponse;
import tcc.aves.dto.UserUpdateRequest;
import tcc.aves.exception.ResourceNotFoundException;
import tcc.aves.repository.UserRecord;
import tcc.aves.repository.UserRepository;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse create(UserRequest request) {
        UserRecord saved = userRepository.create(
                request.name(), request.email(), passwordEncoder.encode(request.password()));
        return toResponse(saved);
    }

    public UserResponse findById(String id) {
        return userRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }

    public UserResponse update(String id, UserUpdateRequest request) {
        UserRecord saved = userRepository.update(id, request.name(), request.email());
        return toResponse(saved);
    }

    public void delete(String id) {
        userRepository.delete(id);
    }

    private UserResponse toResponse(UserRecord user) {
        return new UserResponse(
                user.id(),
                user.name(),
                user.email(),
                user.createdAt(),
                user.updatedAt(),
                user.role()
        );
    }
}
