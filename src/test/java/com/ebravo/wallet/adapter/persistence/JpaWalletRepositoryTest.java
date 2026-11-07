package com.ebravo.wallet.adapter.persistence;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.application.WalletRepository;
import com.ebravo.wallet.application.WalletRepositoryContract;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class JpaWalletRepositoryTest implements WalletRepositoryContract {

    @Autowired
    private WalletRepository repository;

    @Override
    public WalletRepository repository() {
        return repository;
    }
}
