package denny.ai.agent.domain.service.stock;

import denny.ai.agent.domain.model.valobj.stock.StockNameRecord;

import java.util.List;

/**
 * 股票名称目录来源。
 */
public interface StockNameSource {

    List<StockNameRecord> loadActiveStockNames();

    List<StockNameRecord> findByExactName(String stockName);
}
