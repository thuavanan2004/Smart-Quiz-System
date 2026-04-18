package vn.smartquiz.auth.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import vn.smartquiz.auth.domain.user.EmailVerificationToken;

@Repository
public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, byte[]> {

  Optional<EmailVerificationToken> findByTokenHashAndPurpose(byte[] tokenHash, String purpose);
}
