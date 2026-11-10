package com.ebravo.wallet.adapter.config;

import com.ebravo.wallet.application.IdempotencyKeys;
import com.ebravo.wallet.application.Ledger;
import com.ebravo.wallet.application.Transactions;
import com.ebravo.wallet.application.WalletMetrics;
import com.ebravo.wallet.application.WalletRepository;
import com.ebravo.wallet.application.WalletService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the framework-free use cases as beans. */
@Configuration(proxyBeanMethods = false)
class ApplicationConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    WalletService walletService(
            WalletRepository wallets,
            Ledger ledger,
            IdempotencyKeys keys,
            Transactions transactions,
            WalletMetrics metrics,
            Clock clock) {
        return new WalletService(wallets, ledger, keys, transactions, metrics, clock);
    }
}
