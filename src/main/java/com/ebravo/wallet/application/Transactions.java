package com.ebravo.wallet.application;

import java.util.function.Supplier;

/** The boundary of a use case: everything the work writes is applied, or none of it is. */
public interface Transactions {

    <T> T inTransaction(Supplier<T> work);
}
