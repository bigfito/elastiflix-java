package com.elastiflix.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds the baseline response security headers.
 *
 * <p>The policy allows no inline script or style and no third-party origin for either.
 * That is possible because the stylesheet and the single behaviour script are both served
 * from {@code src/main/resources/static} — the page previously loaded Tailwind's browser
 * build from {@code cdn.tailwindcss.com}, configured it from an inline {@code <script>},
 * and wired its controls with inline {@code on*} attributes, all of which forced
 * {@code 'unsafe-inline'}.
 *
 * <p>Images are the one exception: posters come from TMDB and a self-hosted index may point
 * anywhere, so {@code img-src} permits any HTTPS origin — but never plain HTTP.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            // No 'unsafe-inline' and no CDN: the stylesheet and the one script are both served
            // from /static. This is what makes a `javascript:` URL inert even if one reached an
            // href — the service layer strips them as well, but the policy no longer depends on it.
            "script-src 'self'",
            "style-src 'self'",
            // Posters and backdrops are served by TMDB, and a self-hosted index may
            // point anywhere over HTTPS — but never over plain HTTP.
            "img-src 'self' https: data:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
            "base-uri 'self'",
            "object-src 'none'");

    /** The app uses none of these, so switch them off rather than leave them at the browser default. */
    static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

    /** One year, the minimum a browser will accept for preloading. */
    static final String STRICT_TRANSPORT_SECURITY = "max-age=31536000; includeSubDomains";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        // Only over HTTPS. Sending HSTS from a plain-HTTP dev server is at best ignored and at
        // worst pins localhost to https:// in the developer's browser for a year.
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", STRICT_TRANSPORT_SECURITY);
        }
        filterChain.doFilter(request, response);
    }
}
