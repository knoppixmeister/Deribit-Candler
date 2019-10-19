package candler;

import org.jfree.data.time.ohlc.*;

public interface GetOHLCsListener {
	public void onOHLCReceived(OHLCSeries ohlcs);
}
