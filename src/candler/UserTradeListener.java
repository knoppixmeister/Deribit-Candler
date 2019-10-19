package candler;

public interface UserTradeListener {
	void onNewTrade(final UserTrade trade, final String instrument, final String rawData);
}
