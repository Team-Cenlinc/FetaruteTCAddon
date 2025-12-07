package org.fetarute.fetaruteTCAddon.company.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.fetarute.fetaruteTCAddon.company.model.Company;
import org.fetarute.fetaruteTCAddon.company.model.Line;
import org.fetarute.fetaruteTCAddon.company.model.Operator;
import org.fetarute.fetaruteTCAddon.company.model.Route;
import org.fetarute.fetaruteTCAddon.company.model.Station;
import org.fetarute.fetaruteTCAddon.company.repository.CompanyRepository;
import org.fetarute.fetaruteTCAddon.company.repository.LineRepository;
import org.fetarute.fetaruteTCAddon.company.repository.OperatorRepository;
import org.fetarute.fetaruteTCAddon.company.repository.RouteRepository;
import org.fetarute.fetaruteTCAddon.company.repository.StationRepository;
import org.fetarute.fetaruteTCAddon.storage.api.StorageProvider;

/**
 * 命令/GUI 侧的查询门面，统一按 code 优先、UUID 兜底的解析逻辑。
 * <p>所有查询均遵循“先尝试 UUID，未命中则按 code 查询”，避免命令层重复解析。</p>
 */
public final class CompanyQueryService {

    private final StorageProvider storageProvider;

    public CompanyQueryService(StorageProvider storageProvider) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
    }

    /**
     * 根据 code 或 UUID 查询公司；UUID 命中优先，未命中再按 code 查找。
     */
    public Optional<Company> findCompany(String codeOrId) {
        Objects.requireNonNull(codeOrId, "codeOrId");
        Optional<UUID> uuid = tryParseUuid(codeOrId);
        CompanyRepository repo = storageProvider.companies();
        if (uuid.isPresent()) {
            Optional<Company> byId = repo.findById(uuid.get());
            if (byId.isPresent()) {
                return byId;
            }
        }
        return repo.findByCode(codeOrId);
    }

    /**
     * 在指定公司下按 code/UUID 查询运营商，支持控制台/玩家输入的混合格式。
     */
    public Optional<Operator> findOperator(UUID companyId, String codeOrId) {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(codeOrId, "codeOrId");
        Optional<UUID> uuid = tryParseUuid(codeOrId);
        OperatorRepository repo = storageProvider.operators();
        if (uuid.isPresent()) {
            Optional<Operator> byId = repo.findById(uuid.get());
            if (byId.isPresent()) {
                return byId;
            }
        }
        return repo.findByCompanyAndCode(companyId, codeOrId);
    }

    /**
     * 在指定运营商下按 code/UUID 查询线路，命中 UUID 后不再 fallback code。
     */
    public Optional<Line> findLine(UUID operatorId, String codeOrId) {
        Objects.requireNonNull(operatorId, "operatorId");
        Objects.requireNonNull(codeOrId, "codeOrId");
        Optional<UUID> uuid = tryParseUuid(codeOrId);
        LineRepository repo = storageProvider.lines();
        if (uuid.isPresent()) {
            Optional<Line> byId = repo.findById(uuid.get());
            if (byId.isPresent()) {
                return byId;
            }
        }
        return repo.findByOperatorAndCode(operatorId, codeOrId);
    }

    /**
     * 在指定运营商下按 code/UUID 查询站点。
     */
    public Optional<Station> findStation(UUID operatorId, String codeOrId) {
        Objects.requireNonNull(operatorId, "operatorId");
        Objects.requireNonNull(codeOrId, "codeOrId");
        Optional<UUID> uuid = tryParseUuid(codeOrId);
        StationRepository repo = storageProvider.stations();
        if (uuid.isPresent()) {
            Optional<Station> byId = repo.findById(uuid.get());
            if (byId.isPresent()) {
                return byId;
            }
        }
        return repo.findByOperatorAndCode(operatorId, codeOrId);
    }

    /**
     * 在指定线路下按 code/UUID 查询 Route。
     */
    public Optional<Route> findRoute(UUID lineId, String codeOrId) {
        Objects.requireNonNull(lineId, "lineId");
        Objects.requireNonNull(codeOrId, "codeOrId");
        Optional<UUID> uuid = tryParseUuid(codeOrId);
        RouteRepository repo = storageProvider.routes();
        if (uuid.isPresent()) {
            Optional<Route> byId = repo.findById(uuid.get());
            if (byId.isPresent()) {
                return byId;
            }
        }
        return repo.findByLineAndCode(lineId, codeOrId);
    }

    private Optional<UUID> tryParseUuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
