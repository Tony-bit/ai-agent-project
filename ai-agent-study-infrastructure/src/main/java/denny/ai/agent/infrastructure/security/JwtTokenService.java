package denny.ai.agent.infrastructure.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import denny.ai.agent.domain.auth.AuthUser;
import lombok.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;

@Service
public class JwtTokenService {

    private static final String ISSUER = "ai-agent-study";

    private final JwtProperties properties;
    private final Algorithm algorithm;
    private final JWTVerifier verifier;

    public JwtTokenService(JwtProperties properties) {
        this.properties = properties;
        this.algorithm = Algorithm.HMAC256(properties.getSecret());
        this.verifier = JWT.require(algorithm).withIssuer(ISSUER).build();
    }

    public IssuedToken issue(AuthUser user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(properties.getExpiresInSeconds());
        String accessToken = JWT.create()
                .withIssuer(ISSUER)
                .withSubject(user.getUserId())
                .withClaim("userType", user.getUserType().name())
                .withIssuedAt(Date.from(issuedAt))
                .withExpiresAt(Date.from(expiresAt))
                .sign(algorithm);
        return new IssuedToken(accessToken, "Bearer", properties.getExpiresInSeconds());
    }

    public TokenIdentity verify(String token) {
        DecodedJWT jwt = verifier.verify(token);
        return new TokenIdentity(jwt.getSubject(), jwt.getClaim("userType").asString());
    }

    @Value
    public static class IssuedToken {
        String accessToken;
        String tokenType;
        long expiresIn;
    }

    @Value
    public static class TokenIdentity {
        String userId;
        String userType;
    }
}
