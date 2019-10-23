package deribit.candler;

import java.util.concurrent.*;

public interface OrderBookListener {
	public void onOrderBookChange(ConcurrentHashMap<String, ConcurrentSkipListMap<Double, Double>> orderBook);
}
