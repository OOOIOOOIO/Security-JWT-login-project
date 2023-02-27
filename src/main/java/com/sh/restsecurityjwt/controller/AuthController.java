package com.sh.restsecurityjwt.controller;

import com.sh.restsecurityjwt.config.jwt.JwtUtils;
import com.sh.restsecurityjwt.controller.dto.request.LoginRequestDto;
import com.sh.restsecurityjwt.controller.dto.request.SignUpRequestDto;
import com.sh.restsecurityjwt.controller.dto.response.MessageResponseDto;
import com.sh.restsecurityjwt.controller.dto.response.ReAccessTokenResponseDto;
import com.sh.restsecurityjwt.controller.dto.response.UserInfoResponseDto;
import com.sh.restsecurityjwt.domain.ERole;
import com.sh.restsecurityjwt.domain.RefreshToken;
import com.sh.restsecurityjwt.domain.Role;
import com.sh.restsecurityjwt.domain.User;
import com.sh.restsecurityjwt.exception.type.TokenRefreshException;
import com.sh.restsecurityjwt.repository.RoleRepository;
import com.sh.restsecurityjwt.repository.UserRepository;
import com.sh.restsecurityjwt.service.RefreshTokenService;
import com.sh.restsecurityjwt.service.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;


@CrossOrigin(origins = "*", maxAge = 3600) // 60분
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder encoder;
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;

    /**
     * 로그인
     *
     */
    @PostMapping("/signin")
    public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequestDto loginRequestDto) {

        // 인증
        Authentication authentication = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(loginRequestDto.getUsername(), loginRequestDto.getPassword()));

        // SecurityContext에 올림
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 유저 정보 가져오기
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();

        List<String> roles = userDetails.getAuthorities().stream()
                .map(item -> item.getAuthority())
                .collect(Collectors.toList());

        // refresh token 조회
        Optional<RefreshToken> getRefreshToken = refreshTokenService.findByUserId(userDetails.getUserId());

        // refresh token 존재한다면
        if(getRefreshToken.isPresent()){
            // 만료 시간 확인(유효할 경우 통과)
            refreshTokenService.verifyExpiration(getRefreshToken.get());

            // refreshToken 삭제
            refreshTokenService.deleteByUserId(userDetails.getUserId());
        }


        // refreshToken db 생성 및 저장
        RefreshToken refreshToken =  refreshTokenService.createRefreshToken(userDetails.getUserId());

        // jwt 생성
        String accessToken = jwtUtils.generateTokenFromUsername(userDetails.getUsername());


        /**
         * body에 유저 정보 반환
         * username
         *
         * roles
         * accessToken
         * refreshToken
         */
        return ResponseEntity.ok()
                .body(new UserInfoResponseDto(userDetails.getUserId(),
                        userDetails.getUsername(),
                        userDetails.getEmail(),
                        roles,
                        accessToken,
                        refreshToken.getToken()));

    }


    /**
     * 회원가입(role 필수)
     *
     * 기존 사용자 이름/이메일 확인
     * 새 사용자 생성(역할을 지정하지 않은 경우 ROLE_USER 사용)
     * UserRepository를 사용하여 사용자를 데이터베이스에 저장
     */
    @PostMapping("/signup")
    public ResponseEntity<?> registerUser(@Valid @RequestBody SignUpRequestDto signUpRequestDto) {

        // 유효성 검사
        // username
        if (userRepository.existsByUsername(signUpRequestDto.getUsername())) {
            return ResponseEntity.badRequest().body(new MessageResponseDto("ERROR : USERNAME IS ALREADY TAKEN")); // 400 error
        }
        // email
        if (userRepository.existsByEmail(signUpRequestDto.getEmail())) {
            return ResponseEntity.badRequest().body(new MessageResponseDto("Error: Email is already in use!"));
        }

        // 유저 생성
        User user = new User(signUpRequestDto.getUsername(),
                signUpRequestDto.getEmail(),
                encoder.encode(signUpRequestDto.getPassword()));

        Set<String> strRoles = signUpRequestDto.getRole();

        Set<Role> roles = new HashSet<>();

        if (strRoles == null) {
            Role userRole = roleRepository.findByName(ERole.ROLE_USER)
                    .orElseThrow(() -> new RuntimeException("ERROR : ROLE IS NOT FOUND"));

            roles.add(userRole);
        } else {
            strRoles.forEach(role -> {
                switch (role) {
                    case "admin":
                        Role adminRole = roleRepository.findByName(ERole.ROLE_ADMIN)
                                .orElseThrow(() -> new RuntimeException("Error: Role is not found."));
                        roles.add(adminRole);

                        break;
                    case "mod":
                        Role modRole = roleRepository.findByName(ERole.ROLE_MODERATOR)
                                .orElseThrow(() -> new RuntimeException("Error: Role is not found."));
                        roles.add(modRole);

                        break;
                    default:
                        Role userRole = roleRepository.findByName(ERole.ROLE_USER)
                                .orElseThrow(() -> new RuntimeException("Error: Role is not found."));
                        roles.add(userRole);
                }
            });
        }

        user.setRoles(roles);
        userRepository.save(user);

        return ResponseEntity.ok(new MessageResponseDto("USER REGISTERED SUCCESSFULLY!"));

    }

    /**
     * 로그아웃
     * 리프레쉬 토큰 삭제
     */
    @PostMapping("signout")
    public ResponseEntity<?> logoutUser(){

        Object principle = SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        if (principle.toString() != "anonymousUser") {
            Long userId = ((UserDetailsImpl) principle).getUserId();
            refreshTokenService.deleteByUserId(userId);
        }

        return ResponseEntity.ok()
                .body(new MessageResponseDto("You've been signed out!"));
    }

    /**
     * Access token 만료
     * J005 일 때(J002 ~ J004때는 다시 로그인 시키자!)
     * 토큰 만료 시 Access token 재발급 API
     */

    @PostMapping("/access-token")
    public ResponseEntity<?> refreshtoken(HttpServletRequest request) {
        // refreshToken 조회(uuid)
        String refreshToken = jwtUtils.getJwtRefreshFromHeader(request);

        // 존재한다면(시간 비교)
        if ((refreshToken != null) && (refreshToken.length() > 0)) {

            // db 조회
            return refreshTokenService.findByToken(refreshToken)
                    .map(token -> refreshTokenService.verifyExpiration(token)) // 만료시간 검증, db에서 삭제 후 403 error 던짐
                    .map(refreshToken1 -> refreshToken1.getUser()) // 만료가 아닐 경우 그대로 토큰 리턴
                    .map(user -> {
                        // accessToken 재생성
                        String accessToken = jwtUtils.generateTokenFromUsername(user.getUsername()); // lazy 로딩

                        return ResponseEntity.ok()
                                .body(new ReAccessTokenResponseDto("Token is refreshed successfully!", accessToken));
                    })
                    .orElseThrow(() -> new TokenRefreshException(refreshToken, "Refresh token is not in database!"));
        }

        return ResponseEntity.badRequest().body(new MessageResponseDto("Refresh Token is empty!"));
    }
    
        /**
     * Refresh Token 만료
     *
     * J001 일 때
     * 403 에러 타기 전 db에서 삭제됨
     * 
     */
    @PostMapping("/refresh-token")
    public ResponseEntity<?> refreshToken(HttpServletRequest request) {
        log.info("======== refresh token 만료 ==========");

        Authentication authentication = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(loginRequestDto.getUsername(), loginRequestDto.getPassword()));

        // SecurityContext에 올림
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 유저 정보 가져오기
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();

        String jwt = jwtUtils.getJwtFromHeader(request);
        String username = jwtUtils.getUserNameFromJwtToken(jwt);


        // refreshToken db 생성 및 저장
        RefreshToken refreshToken =  refreshTokenService.createRefreshToken((userDetails.getUserId());
        log.info("=========== refresh token 생성 : " + refreshToken.getToken() + " ===============");

        // jwt 생성
         String accessToken = jwtUtils.generateTokenFromUsername(email);
        log.info("=========== access token 생성 : " + accessToken + " ===============");


        return ResponseEntity.ok()
                .body(new UserInfoResponseDto(
                        accessToken,
                        refreshToken.getToken()));
    }
}
