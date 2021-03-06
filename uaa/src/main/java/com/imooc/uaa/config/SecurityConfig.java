package com.imooc.uaa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imooc.uaa.security.auth.ExternalAuthenticationProvider;
import com.imooc.uaa.security.auth.rest.RestAuthenticationFailureHandler;
import com.imooc.uaa.security.auth.rest.RestAuthenticationFilter;
import com.imooc.uaa.security.auth.rest.RestAuthenticationSuccessHandler;
import com.imooc.uaa.security.dsl.ClientErrorLoggingConfigurer;
import com.imooc.uaa.security.jwt.JwtFilter;
import com.imooc.uaa.security.userdetails.UserDetailsPasswordServiceImpl;
import com.imooc.uaa.security.userdetails.UserDetailsServiceImpl;
import com.imooc.uaa.service.UserCacheService;
import com.imooc.uaa.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.MessageDigestPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.zalando.problem.spring.web.advice.security.SecurityProblemSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

@RequiredArgsConstructor
@EnableWebSecurity(debug = true)
@Configuration
@Order(99)
@Import(SecurityProblemSupport.class)
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    private final SecurityProblemSupport problemSupport;
    private final JwtFilter jwtFilter;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final UserDetailsServiceImpl userDetailsServiceImpl;
    private final UserDetailsPasswordServiceImpl userDetailsPasswordServiceImpl;
    private final UserCacheService userCacheService;
    private final JwtUtil jwtUtil;

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            .httpBasic(AbstractHttpConfigurer::disable)
            .csrf(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource())) // ????????????
            .sessionManagement(sessionManagement -> sessionManagement
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptionHandling -> exceptionHandling
                .authenticationEntryPoint(problemSupport)
                .accessDeniedHandler(problemSupport))
            .authorizeRequests(authorizeRequests -> authorizeRequests
                .mvcMatchers("/", "/authorize/**").permitAll()
                .mvcMatchers("/api/users/**")
                            .access("hasRole('ADMIN') or @userService.isValidUser(authentication, #username)")
                .antMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            // .addFilterBefore(new LDAPAuthorizationFilter(new AntPathRequestMatcher("/api/**")), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
//            .addFilterAt(restAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
        ;
    }

    @Override
    public void configure(WebSecurity web) {
        web
            .ignoring()
            .antMatchers("/resources/**", "/static/**", "/public/**", "/h2-console/**")
            .requestMatchers(PathRequest.toStaticResources().atCommonLocations());
    }

    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth
            .authenticationProvider(new ExternalAuthenticationProvider());

        auth
            .userDetailsService(userDetailsServiceImpl) // ?????? AuthenticationManager ?????? userService
            .passwordEncoder(passwordEncoder()) // ?????? AuthenticationManager ?????? userService
            .userDetailsPasswordManager(userDetailsPasswordServiceImpl); // ??????????????????????????????
    }

    /**
     * ????????? Spring Boot ?????????????????????????????? CORS
     * ?????? https://docs.spring.io/spring/docs/current/spring-framework-reference/web.html#mvc-cors
     * Mvc ?????????????????? WebMvcConfig ????????????
     *
     * @return CorsConfigurationSource
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // ???????????????????????????
        if (environment.acceptsProfiles(Profiles.of("dev"))) {
            configuration.setAllowedOrigins(Collections.singletonList("http://localhost:4001"));
        } else {
            configuration.setAllowedOrigins(Collections.singletonList("https://uaa.imooc.com"));
        }
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Collections.singletonList("*"));
        configuration.addExposedHeader("X-Authenticate");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public ClientErrorLoggingConfigurer clientErrorLogging() {
        return new ClientErrorLoggingConfigurer(new ArrayList<>());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // ????????????????????? Id
        val idForEncode = "bcrypt";
        // ???????????????????????????
        val encoders = Map.of(
            idForEncode, new BCryptPasswordEncoder(),
            "SHA-1", new MessageDigestPasswordEncoder("SHA-1")
        );
        return new DelegatingPasswordEncoder(idForEncode, encoders);
    }

    @Bean
    @Override
    protected AuthenticationManager authenticationManager() throws Exception {
        return super.authenticationManager();
    }

    @Bean
    public RestAuthenticationFilter restAuthenticationFilter() throws Exception {
        val filter = new RestAuthenticationFilter(objectMapper, userDetailsServiceImpl, userCacheService);
        filter.setAuthenticationSuccessHandler(new RestAuthenticationSuccessHandler(jwtUtil));
        filter.setAuthenticationFailureHandler(new RestAuthenticationFailureHandler(objectMapper));
        filter.setAuthenticationManager(authenticationManager());
        filter.setFilterProcessesUrl("/authorize/login");
        return filter;
    }
}
