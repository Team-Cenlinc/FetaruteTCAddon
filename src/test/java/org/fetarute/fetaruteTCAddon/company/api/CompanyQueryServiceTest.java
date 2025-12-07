package org.fetarute.fetaruteTCAddon.company.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.CompanyStatus;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class CompanyQueryServiceTest {

    private StorageProvider storageProvider;
    private CompanyRepository companyRepository;
    private CompanyQueryService queryService;
    private UUID ownerId;
    private Instant now;

    @BeforeEach
    void setUp() {
        storageProvider = mock(StorageProvider.class);
        companyRepository = mock(CompanyRepository.class);
        when(storageProvider.companies()).thenReturn(companyRepository);
        queryService = new CompanyQueryService(storageProvider);
        ownerId = UUID.randomUUID();
        now = Instant.now();
    }

    @Test
    void findCompanyPrefersUuid() {
        UUID companyId = UUID.randomUUID();
        Company company = new Company(companyId, "C-001", "Alpha", Optional.empty(),
                ownerId, CompanyStatus.ACTIVE, 0L, Map.of(), now, now);
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        Optional<Company> result = queryService.findCompany(companyId.toString());

        assertTrue(result.isPresent());
        assertEquals(company, result.get());
        verify(companyRepository, never()).findByCode(any());
    }

    @Test
    void findCompanyFallsBackToCodeWhenUuidMisses() {
        UUID companyId = UUID.randomUUID();
        when(companyRepository.findById(companyId)).thenReturn(Optional.empty());
        Company byCode = new Company(UUID.randomUUID(), "ALPHA", "Alpha", Optional.empty(),
                ownerId, CompanyStatus.ACTIVE, 0L, Map.of(), now, now);
        when(companyRepository.findByCode("ALPHA")).thenReturn(Optional.of(byCode));

        Optional<Company> result = queryService.findCompany("ALPHA");

        assertTrue(result.isPresent());
        assertEquals(byCode, result.get());
        verify(companyRepository, never()).findById(any());
        verify(companyRepository, times(1)).findByCode("ALPHA");
    }
}
