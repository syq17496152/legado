# 自定义按键

自定义按键用于给正文菜单增加可执行按钮。它由两部分组成：

- `按钮 JS`：短按执行，必须定义 `function run()`
- `登录 UI / 登录 URL`：长按进入登录流程

## 触发方式

### 短按

点击按键时执行 `function run()`。

可用对象：

- `book`：当前书籍
- `chapter`：当前章节
- `content`、`src`、`result`：当前章节正文
- `title`：当前章节标题
- `baseUrl`：当前章节请求地址
- `bookSource`、`readSource`：当前阅读书源
- `button`：自定义按键本身
- `source`：按键执行上下文
- `java`：辅助对象
- `cache`、`cookie`：缓存与 Cookie 工具

### 长按

长按按键会进入登录界面：

- `loginUi` 负责生成登录表单
- 点击登录按钮后执行 `loginUrl` 中的 `function login()`
- 如果 `loginUi` 为空，也可以只写 `loginUrl`

长按登录可用对象：

- `book`
- `chapter`
- `result`：表单数据 Map
- `isLongClick`：是否长按触发
- `source`
- `java`
- `cache`
- `cookie`

## `loginUi` 写法

`loginUi` 里如果要写固定文字，必须用单引号包起来，否则会被当成 JS 表达式执行。

正确写法：

```js
@js:
JSON.stringify([
  {name:"bookName", type:"text", viewName:"'书名'", default:book ? book.name : ""},
  {name:"chapterTitle", type:"text", viewName:"'章节名'", default:chapter ? chapter.title : ""},
  {name:"submit", type:"button", viewName:"'提交'", action:"java.toast(JSON.stringify(result))"}
])
```

## 测试例程

### 按钮 JS

```js
function run() {
  java.toast(
    "书名：" + (book ? book.name : "book=null") +
    "\n章节：" + (chapter ? chapter.title : "chapter=null") +
    "\n正文长度：" + ((content || "").length)
  );
  return true;
}
```

### 登录 URL

```js
function login() {
  java.toast(
    "登录提交\n书名：" + (book ? book.name : "book=null") +
    "\n章节：" + (chapter ? chapter.title : "chapter=null") +
    "\n表单：" + JSON.stringify(result)
  );
  return true;
}
```

## 注意

- 只有 `function run()` 才会被短按执行
- 只有 `function login()` 才会被登录按钮执行
- 不建议在这里直接修改正文内容
- 段落规则和自定义按键都走同一套登录界面，`viewName` 的固定文本同样要写成 `"'文本'"` 的形式
