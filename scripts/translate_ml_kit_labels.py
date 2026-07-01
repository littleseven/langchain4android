#!/usr/bin/env python3
"""
批量翻译 ML Kit Image Labeler 的 400+ 英文标签为中文。

用法：
    python scripts/translate_ml_kit_labels.py \
        --input scripts/ml_kit_labels_input.txt \
        --output scripts/ml_kit_labels_zh.json

注意：
    本脚本调用本地配置的 LLM API（Anthropic 兼容接口）进行翻译。
    仅发送英文标签文本，不发送用户照片或 TAG，遵守 PRIVACY 红线。
"""

import argparse
import json
import os
import re
import time
from pathlib import Path
from typing import List, Dict

import requests


def load_labels(path: Path) -> List[str]:
    with open(path, "r", encoding="utf-8") as f:
        return [line.strip() for line in f if line.strip()]


def parse_translations(text: str, expected: List[str]) -> Dict[str, str]:
    """尝试从模型输出解析 JSON 或 "English -> 中文" 格式。"""
    # 先尝试提取 JSON 代码块
    json_match = re.search(r"```json\s*(\{.*?\})\s*```", text, re.DOTALL)
    if json_match:
        try:
            return json.loads(json_match.group(1))
        except json.JSONDecodeError:
            pass

    # 再尝试整个回复是 JSON
    try:
        data = json.loads(text.strip())
        if isinstance(data, dict):
            return data
    except json.JSONDecodeError:
        pass

    # 逐行解析 "Team -> 团队" 或 "Team: 团队"
    result = {}
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        # 去掉行首标号如 "1. Team -> 团队"
        line = re.sub(r"^\d+\.\s*", "", line)
        # 去掉引号
        line = line.replace('"', "")
        for sep in ["->", "=", ":", "："]:
            if sep in line:
                parts = line.split(sep, 1)
                en = parts[0].strip()
                zh = parts[1].strip()
                if en and zh:
                    result[en] = zh
                break
    return result


def translate_batch(labels: List[str], api_key: str, base_url: str, model: str) -> Dict[str, str]:
    prompt = (
        "请将以下 ML Kit 图像标签翻译成最简洁、最常用的中文，用于图片搜索。\n"
        "输出必须是纯 JSON 对象，key 为英文原词，value 为中文翻译，不要任何解释。\n"
        "格式示例：{\"Team\": \"团队\", \"Bonfire\": \"篝火\", \"Comics\": \"漫画\"}\n\n"
        "待翻译标签：\n" + json.dumps(labels, ensure_ascii=False, indent=2)
    )

    headers = {
        "x-api-key": api_key,
        "Content-Type": "application/json",
        "anthropic-version": "2023-06-01",
    }

    payload = {
        "model": model,
        "max_tokens": 4096,
        "messages": [
            {"role": "user", "content": prompt}
        ],
    }

    url = f"{base_url.rstrip('/')}/v1/messages"
    response = requests.post(url, headers=headers, json=payload, timeout=120)
    response.raise_for_status()
    data = response.json()
    text_parts = [item["text"] for item in data["content"] if item.get("type") == "text"]
    if not text_parts:
        raise RuntimeError(f"No text content in response: {data}")
    return parse_translations("\n".join(text_parts), labels)


def main() -> None:
    parser = argparse.ArgumentParser(description="Translate ML Kit labels to Chinese")
    parser.add_argument("--input", type=Path, default=Path("scripts/ml_kit_labels_input.txt"))
    parser.add_argument("--output", type=Path, default=Path("scripts/ml_kit_labels_zh.json"))
    parser.add_argument("--batch-size", type=int, default=50)
    args = parser.parse_args()

    api_key = os.environ.get("ANTHROPIC_AUTH_TOKEN")
    base_url = os.environ.get("ANTHROPIC_BASE_URL", "https://api.anthropic.com")
    model = os.environ.get("ANTHROPIC_MODEL", "claude-3-haiku-20240307")

    if not api_key:
        raise RuntimeError("ANTHROPIC_AUTH_TOKEN not set")

    labels = load_labels(args.input)
    print(f"Loaded {len(labels)} labels")

    all_translations: Dict[str, str] = {}
    batch_size = args.batch_size

    for i in range(0, len(labels), batch_size):
        batch = labels[i:i + batch_size]
        print(f"Translating batch {i // batch_size + 1}/{(len(labels) + batch_size - 1) // batch_size}: {len(batch)} labels")
        translations = translate_batch(batch, api_key, base_url, model)
        print(f"  Got {len(translations)} translations")
        all_translations.update(translations)
        if i + batch_size < len(labels):
            time.sleep(1)

    # 补全未翻译的项
    for label in labels:
        if label not in all_translations:
            all_translations[label] = label
            print(f"  Warning: missing translation for '{label}', using original")

    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(all_translations, f, ensure_ascii=False, indent=2)

    print(f"Wrote {len(all_translations)} translations to {args.output}")


if __name__ == "__main__":
    main()
