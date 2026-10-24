package com.memora.application.identity;

public record Identity(String issuer, String subject) {
    public Identity {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("Issuer obrigatório.");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Subject obrigatório.");
        }
    }
}
