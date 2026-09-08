// @name: CloneTTS
// @schema: 1
// @capabilities: speed
// @defaultSpeed: 50
// 前置：在 CloneTTS App 内克隆音色后，将音色 UUID 填入 CONFIG.voice（或在选角模板中指定）
var CONFIG = {
    endpoint: "http://127.0.0.1:8080",
    voice: ""
};

function synthesize(text, voice, params, options, ctx) {
    // CloneTTS speed 语义=倍速（0.5~2.0），params.rate 默认 1.0
    var speed = ((params.rate || 1)).toFixed(2);
    var v = voice || CONFIG.voice;
    return {
        url: CONFIG.endpoint + "/api/tts?text=" + encodeURIComponent(text) +
            "&voice=" + encodeURIComponent(v) + "&speed=" + speed,
        method: "GET"
    };
}

function voices() {
    // 官方集束接口：返回本机全部克隆音色（legado 引擎集束格式）
    return { type: "url", url: CONFIG.endpoint + "/api/legado/all" };
}

function options() {
    return [];
}
