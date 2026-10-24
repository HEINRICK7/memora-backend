package com.memora.application.ports;

import com.memora.application.identity.Identity;

import java.util.UUID;

public interface UserRepository {
    UUID resolveOrCreate(Identity identity);
}
