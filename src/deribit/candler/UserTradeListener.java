package deribit.candler;

public interface UserTradeListener {
	public enum KIND {
		TRADE_EVENT,
		GET_EVENT
	}

	void onNewTrade(final KIND kind, final UserTrade trade, final String instrument, final String rawData);
}
