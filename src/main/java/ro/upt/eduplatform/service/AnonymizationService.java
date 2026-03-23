package ro.upt.eduplatform.service;

import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class AnonymizationService {

    public String anonymizeName(String name) {
        if (name == null || name.isBlank()) return "ANONIM_NECUNOSCUT";
        String normalized = name.trim().toUpperCase();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("ID_");
            for (int i = 0; i < 6; i++) hex.append(String.format("%02x", hashBytes[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
