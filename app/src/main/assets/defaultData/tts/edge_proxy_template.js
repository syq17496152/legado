// @name: Edge-TTS 代理（模板）
// @schema: 1
// @capabilities: speed
// @defaultSpeed: 50
// 自建 Edge-TTS 代理后填入端点（本应用不内置任何第三方端点）
var CONFIG = {
    endpoint: ""
};

function synthesize(text, voice, params, options, ctx) {
    return {
        url: CONFIG.endpoint + "?text=" + encodeURIComponent(text),
        method: "GET"
    };
}

function voices() {
    return [];
}

function options() {
    return [];
}
