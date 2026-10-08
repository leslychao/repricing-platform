package ru.oritas.repricer.app.files;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.oritas.repricer.platform.FileWorkGate;
import ru.oritas.repricer.platform.OutboxService;

/** Admission precedes Servlet multipart spooling, so waiting uploads cannot fill the temp disk. */
public final class UploadAdmissionFilter extends OncePerRequestFilter {
  static final String PERMIT_ATTRIBUTE = UploadAdmissionFilter.class.getName() + ".permit";
  private final FileWorkGate gate;
  private final Clock clock;
  private final OutboxService outbox;

  public UploadAdmissionFilter(FileWorkGate gate, Clock clock, OutboxService outbox) {
    this.gate = gate;
    this.clock = clock;
    this.outbox = outbox;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getMethod().equals("POST")
        || !request.getRequestURI().equals("/api/v1/imports");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!outbox.heavyAllowed()) {
      response.setStatus(429);
      response.setHeader("Retry-After", "30");
      response.setContentType("application/problem+json");
      response
          .getWriter()
          .write(
              "{\"code\":\"OUTBOX_BACKPRESSURE\",\"message\":\"Очередь событий перегружена."
                  + " Повторите загрузку позже.\",\"status\":429}");
      return;
    }
    var acquired = gate.tryAcquire(clock.instant().plusSeconds(300));
    if (acquired.isEmpty()) {
      response.setStatus(429);
      response.setHeader("Retry-After", "5");
      response.setContentType("application/problem+json");
      response
          .getWriter()
          .write(
              "{\"code\":\"FILE_SLOTS_BUSY\",\"message\":\"Обработка файлов занята. Повторите"
                  + " загрузку.\",\"status\":429}");
      return;
    }
    try (var permit = acquired.orElseThrow()) {
      request.setAttribute(PERMIT_ATTRIBUTE, permit);
      chain.doFilter(request, response);
    }
  }
}
