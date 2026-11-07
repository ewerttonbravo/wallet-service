package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.application.WalletService;
import com.ebravo.wallet.domain.OwnerId;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own wallet, addressed as {@code me}: the owner always comes from the token. */
@RestController
@RequestMapping("/wallets")
class WalletController {

    private static final URI OWN_WALLET = URI.create("/wallets/me");

    private final WalletService wallets;

    WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    @PostMapping
    ResponseEntity<WalletResponse> open(@AuthenticationPrincipal Jwt token) {
        return ResponseEntity.created(OWN_WALLET).body(WalletResponse.of(wallets.open(ownerOf(token))));
    }

    @GetMapping("/me")
    WalletResponse wallet(@AuthenticationPrincipal Jwt token) {
        return WalletResponse.of(wallets.get(ownerOf(token)));
    }

    @GetMapping("/me/balance")
    BalanceResponse balance(@AuthenticationPrincipal Jwt token) {
        return BalanceResponse.of(wallets.get(ownerOf(token)).balance());
    }

    private static OwnerId ownerOf(Jwt token) {
        return new OwnerId(token.getSubject());
    }
}
