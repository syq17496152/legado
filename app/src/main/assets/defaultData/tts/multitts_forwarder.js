// @name: MultiTTS 转发器
// @schema: 1
// @capabilities: speed,volume,pitch
// @defaultSpeed: 50
// 前置：在 MultiTTS App 内开启“转发服务”（默认端口 8774），并授予电池白名单
var CONFIG = {
    endpoint: "http://127.0.0.1:8774"
};

function synthesize(text, voice, params, options, ctx) {
    // MultiTTS 参数域：0~100（50=常态）
    var speed = Math.round((params.rate || 1) * 50);
    var volume = Math.round((params.volume || 1) * 50);
    var pitch = Math.round((params.pitch || 1) * 50);
    return {
        url: CONFIG.endpoint + "/forward?text=" + encodeURIComponent(text) +
            "&speed=" + speed + "&volume=" + volume + "&pitch=" + pitch +
            (voice ? "&voice=" + encodeURIComponent(voice) : ""),
        method: "GET"
    };
}

function voices() {
    return { type: "url", url: CONFIG.endpoint + "/voices" };
}

function options() {
    return [];
}
