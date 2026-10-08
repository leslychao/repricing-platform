package ru.oritas.repricer.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.access.AccessService;
import ru.oritas.repricer.platform.ObjectStorage;

@Component
@ConditionalOnProperty(name = "repricer.mode", havingValue = "api", matchIfMissing = true)
public final class Bootstrap implements ApplicationRunner {
  private final AccessService access;
  private final ObjectStorage storage;
  private final String issuer;
  private final String adminSubject;
  private final String testSubject;

  public Bootstrap(AccessService access, ObjectStorage storage,
      @Value("${repricer.oidc.issuer}") String issuer,
      @Value("${BOOTSTRAP_ADMIN_SUBJECT:}") String adminSubject,
      @Value("${BOOTSTRAP_TEST_SUBJECT:}") String testSubject) {
    this.access = access;
    this.storage = storage;
    this.issuer = issuer;
    this.adminSubject = adminSubject;
    this.testSubject = testSubject;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    storage.verifyVersioning();
    if (!adminSubject.isBlank() && !testSubject.isBlank()) {
      access.initManagedUsers(issuer, adminSubject, testSubject);
    }
  }
}
