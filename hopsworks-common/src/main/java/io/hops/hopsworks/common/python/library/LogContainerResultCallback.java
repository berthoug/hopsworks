package io.hops.hopsworks.common.python.library;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;

public class LogContainerResultCallback extends ResultCallback.Adapter<Frame> {

  protected final StringBuffer log = new StringBuffer();

  @Override
  public void onNext(Frame frame) {
    log.append(new String(frame.getPayload()));
  }

  @Override
  public String toString() {
    return log.toString();
  }

}
