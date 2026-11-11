package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.application.WalletService;
import com.ebravo.wallet.domain.CorrelationId;
import com.ebravo.wallet.domain.OwnerId;
import com.ebravo.wallet.domain.WalletTransaction;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** The current balance, or with {@code at} the balance at that past instant. */
    @GetMapping("/me/balance")
    BalanceResponse balance(@AuthenticationPrincipal Jwt token, @RequestParam(required = false) String at) {
        var owner = ownerOf(token);
        if (at == null) {
            return BalanceResponse.of(wallets.get(owner).balance());
        }
        return BalanceResponse.of(wallets.balanceAt(owner, RequestFields.instant(at)));
    }

    @PostMapping("/me/deposits")
    ResponseEntity<TransactionResponse> deposit(
            @AuthenticationPrincipal Jwt token,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) CorrelationId correlationId,
            @RequestHeader(name = RequestFields.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody AmountRequest request) {
        return posted(wallets.deposit(
                ownerOf(token), request.toMoney(), correlationId, RequestFields.idempotencyKey(idempotencyKey)));
    }

    @PostMapping("/me/withdrawals")
    ResponseEntity<TransactionResponse> withdraw(
            @AuthenticationPrincipal Jwt token,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) CorrelationId correlationId,
            @RequestHeader(name = RequestFields.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody AmountRequest request) {
        return posted(wallets.withdraw(
                ownerOf(token), request.toMoney(), correlationId, RequestFields.idempotencyKey(idempotencyKey)));
    }

    @PostMapping("/me/transfers")
    ResponseEntity<TransactionResponse> transfer(
            @AuthenticationPrincipal Jwt token,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) CorrelationId correlationId,
            @RequestHeader(name = RequestFields.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody TransferRequest request) {
        return posted(wallets.transfer(ownerOf(token), request.recipient(), request.toMoney(), correlationId,
                RequestFields.idempotencyKey(idempotencyKey)));
    }

    @GetMapping("/me/transactions")
    TransactionPageResponse transactions(
            @AuthenticationPrincipal Jwt token,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        return TransactionPageResponse.of(
                wallets.transactions(ownerOf(token), RequestFields.pageRequest(page, size)));
    }

    @GetMapping("/me/transactions/{id}")
    TransactionResponse transaction(@AuthenticationPrincipal Jwt token, @PathVariable String id) {
        return TransactionResponse.of(wallets.transaction(ownerOf(token), RequestFields.transactionId(id)));
    }

    private static ResponseEntity<TransactionResponse> posted(WalletTransaction transaction) {
        var location = URI.create("/wallets/me/transactions/" + transaction.id().value());
        return ResponseEntity.created(location).body(TransactionResponse.of(transaction));
    }

    private static OwnerId ownerOf(Jwt token) {
        return new OwnerId(token.getSubject());
    }
}
