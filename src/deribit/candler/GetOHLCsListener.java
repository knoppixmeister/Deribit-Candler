package deribit.candler;

import org.jfree.data.time.ohlc.OHLCSeries;

public interface GetOHLCsListener {
	public void onOHLCReceived(OHLCSeries ohlcs);
}
