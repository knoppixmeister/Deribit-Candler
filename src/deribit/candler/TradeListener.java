package deribit.candler;

public interface TradeListener {
	void onNewTrade(final Trade trade, final String instrument, final String rawData);
}
