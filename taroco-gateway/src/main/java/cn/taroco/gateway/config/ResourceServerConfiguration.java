package cn.taroco.gateway.config;

import cn.taroco.common.config.TarocoOauth2Properties;
import cn.taroco.gateway.handler.TarocoAccessDeniedHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.oauth2.resource.ResourceServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.ExpressionUrlAuthorizationConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableResourceServer;
import org.springframework.security.oauth2.config.annotation.web.configuration.ResourceServerConfigurerAdapter;
import org.springframework.security.oauth2.config.annotation.web.configurers.ResourceServerSecurityConfigurer;
import org.springframework.security.oauth2.provider.expression.OAuth2WebSecurityExpressionHandler;
import org.springframework.security.oauth2.provider.token.TokenStore;
import org.springframework.security.oauth2.provider.token.store.JwtAccessTokenConverter;
import org.springframework.security.oauth2.provider.token.store.JwtTokenStore;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 资源服务器配置
 *
 * @author liuht
 * @date 2017/10/27
 */
@Configuration
@EnableResourceServer
@EnableConfigurationProperties(TarocoOauth2Properties.class)
@Slf4j
public class ResourceServerConfiguration extends ResourceServerConfigurerAdapter {

    private static final String PUBLIC_KEY = "pubkey.txt";

    @Autowired
    private TarocoOauth2Properties oauth2Properties;

    @Autowired
    private OAuth2WebSecurityExpressionHandler expressionHandler;

    @Autowired
    private TarocoAccessDeniedHandler accessDeniedHandler;

    @Autowired
    private ResourceServerProperties resource;

    @Bean
    public TokenStore tokenStore(JwtAccessTokenConverter jwtAccessTokenConverter) {
        return new JwtTokenStore(jwtAccessTokenConverter);
    }

    @Bean
    public JwtAccessTokenConverter jwtAccessTokenConverter() {
        JwtAccessTokenConverter converter = new JwtAccessTokenConverter();
        converter.setVerifierKey(getPubKey());
        return converter;
    }

    /**
     * 获取非对称加密公钥 Key
     * @return 公钥 Key
     */
    private String getPubKey() {
        Resource resource = new ClassPathResource(PUBLIC_KEY);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(resource.getInputStream()))) {
            return br.lines().collect(Collectors.joining("\n"));
        } catch (IOException ioe) {
            try {
                return getKeyFromAuthorizationServer();
            } catch (Exception e) {
                log.error("Get Public Key From Authorization Server Failed.", e);
                throw new IllegalStateException("Get Public Key From Authorization Server Failed.", e);
            }
        }
    }

    /**
     * 通过访问授权服务器获取非对称加密公钥 Key
     * @return 公钥 Key
     */
    private String getKeyFromAuthorizationServer() {
        final RestTemplate keyUriRestTemplate = new RestTemplate();
        final HttpHeaders headers = new HttpHeaders();
        final String username = this.resource.getClientId();
        final String password = this.resource.getClientSecret();
        if (username != null && password != null) {
            final byte[] token = Base64.getEncoder().encode((username + ":" + password).getBytes());
            headers.add("Authorization", "Basic " + new String(token));
        }
        final HttpEntity<Void> request = new HttpEntity<>(headers);
        final String url = this.resource.getJwt().getKeyUri();
        return (String) keyUriRestTemplate
                .exchange(url, HttpMethod.GET, request, Map.class).getBody()
                .get("value");
    }

    @Override
    public void configure(HttpSecurity http) throws Exception {
        //允许使用iframe 嵌套，避免swagger-ui 不被加载的问题
        http.headers().frameOptions().disable();
        ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry registry = http
                .authorizeRequests();
        oauth2Properties.getUrlPermitAll().forEach(url -> registry.antMatchers(url).permitAll());
        // 角色和权限的验证交给拦截器去做, 这里只判断是否登录
        registry.anyRequest()
                .access("@permissionService.hasPermission(request, authentication)");
    }

    @Override
    public void configure(ResourceServerSecurityConfigurer resources) {
        resources.expressionHandler(expressionHandler);
        resources.accessDeniedHandler(accessDeniedHandler);
    }

    /**
     * 配置解决 spring-security-oauth问题
     * https://github.com/spring-projects/spring-security-oauth/issues/730
     *
     * @param applicationContext ApplicationContext
     * @return OAuth2WebSecurityExpressionHandler
     */
    @Bean
    public OAuth2WebSecurityExpressionHandler oAuth2WebSecurityExpressionHandler(ApplicationContext applicationContext) {
        OAuth2WebSecurityExpressionHandler expressionHandler = new OAuth2WebSecurityExpressionHandler();
        expressionHandler.setApplicationContext(applicationContext);
        return expressionHandler;
    }

    /**
     * 加密方式
     */
    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder();
    }
}
