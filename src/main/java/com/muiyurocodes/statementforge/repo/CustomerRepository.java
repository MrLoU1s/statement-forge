package com.muiyurocodes.statementforge.repo;

import com.muiyurocodes.statementforge.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
