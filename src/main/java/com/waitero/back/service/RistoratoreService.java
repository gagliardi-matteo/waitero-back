package com.waitero.back.service;

import com.waitero.back.entity.Ristoratore;
import com.waitero.back.repository.RistoratoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RistoratoreService {

    private final RistoratoreRepository ristoratoreRepository;

    public Optional<Ristoratore> findRistoratoreById(Long id){
        return ristoratoreRepository.findById(id);
    }

}
