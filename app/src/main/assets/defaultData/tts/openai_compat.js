// @name: OpenAI 兼容
// @schema: 1
// @capabilities: speed
// @defaultSpeed: 50
// 在 CONFIG 中填入自建/第三方 OpenAI 兼容 TTS 端点与密钥（数据仅保存在本机）
var CONFIG = {
    endpoint: "",
    apiKey: "",
    voice: "alloy",
    model: "tts-1"
};

function synthesize(text, voice, params, options, ctx) {
    var speed = ((params.rate || 1)).toFixed(2);
    return {
        url: CONFIG.endpoint,
        method: "POST",
        headers: {
            "Authorization": "Bearer " + CONFIG.apiKey,
            "Content-Type": "application/json"
        },
        body: JSON.stringify({
            model: CONFIG.model,
            input: text,
            voice: voice || CONFIG.voice,
            speed: Number(speed),
            response_format: "mp3"
        })
    };
}

function voices() {
    return [];
}

function options() {
    return [];
}
