package ru.oritas.repricer.app;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

final class OriginFilter extends OncePerRequestFilter {
  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
  private final String origin;

  OriginFilter(String origin) {
    this.origin = origin;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    if (!SAFE.contains(request.getMethod()) && !origin.equals(request.getHeader("Origin"))) {
      response.sendError(403);
      return;
    }
    chain.doFilter(request, response);
  }
}
