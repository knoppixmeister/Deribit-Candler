package deribit.candler;

public interface UserAuthListener {
	public void onUserAuth(boolean isUserAuthorized, UserAuthResult userAuthResult);
}
