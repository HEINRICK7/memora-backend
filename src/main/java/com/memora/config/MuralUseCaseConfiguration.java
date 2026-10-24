package com.memora.config;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.mural.CreateMural;
import com.memora.application.mural.GetOwnedMural;
import com.memora.application.mural.ListOwnedMurals;
import com.memora.application.mural.MuralCursorCodec;
import com.memora.application.mural.UpdateMuralMetadata;
import com.memora.application.ports.CurrentIdentity;
import com.memora.application.ports.MuralRepository;
import com.memora.application.ports.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.UUID;

@Configuration
class MuralUseCaseConfiguration {
    @Bean
    CurrentUserId currentUserId(CurrentIdentity identity, UserRepository users) {
        return new CurrentUserId(identity, users);
    }

    @Bean
    MuralCursorCodec muralCursorCodec() {
        return new MuralCursorCodec();
    }

    @Bean
    CreateMural createMural(MuralRepository murals, CurrentUserId currentUserId, Clock clock) {
        return new CreateMural(murals, currentUserId, clock, UUID::randomUUID);
    }

    @Bean
    ListOwnedMurals listOwnedMurals(MuralRepository murals, CurrentUserId currentUserId,
                                    MuralCursorCodec cursors) {
        return new ListOwnedMurals(murals, currentUserId, cursors);
    }

    @Bean
    GetOwnedMural getOwnedMural(MuralRepository murals, CurrentUserId currentUserId) {
        return new GetOwnedMural(murals, currentUserId);
    }

    @Bean
    UpdateMuralMetadata updateMuralMetadata(MuralRepository murals, CurrentUserId currentUserId, Clock clock) {
        return new UpdateMuralMetadata(murals, currentUserId, clock);
    }
}
