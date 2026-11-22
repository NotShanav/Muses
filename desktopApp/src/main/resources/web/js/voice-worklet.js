// voice-worklet.js - runs inside the AudioWorklet thread (loaded by voice.js with audioWorklet.addModule).
// It copies the microphone samples into blocks of 2048 floats and posts them to the page. Nothing else:
// no recognition, no storage. The page owns the blocks and throws them away after the request.
class MusesCapture extends AudioWorkletProcessor {
  constructor() {
    super();
    this.size = 2048;
    this.buf = new Float32Array(this.size);
    this.n = 0;
  }

  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (!channel) return true;
    for (let i = 0; i < channel.length; i++) {
      this.buf[this.n++] = channel[i];
      if (this.n === this.size) {
        const out = this.buf;
        this.buf = new Float32Array(this.size);
        this.n = 0;
        this.port.postMessage(out, [out.buffer]);
      }
    }
    return true;
  }
}

registerProcessor('muses-capture', MusesCapture);
