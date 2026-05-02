---
navigation:
  parent: introduction/index.md
  title: MEケーブル設置ツール
  position: 3
  icon: meplacementtool:me_cable_placement_tool
categories:
  - meplacementtool tools
item_ids:
  - meplacementtool:me_cable_placement_tool
---

# MEケーブル設置ツール

<ItemImage id="meplacementtool:me_cable_placement_tool" scale="4" />

MEケーブル設置ツールは、様々な種類のケーブルと色に対応し、複数の設置モードを備えており、素早い配線を可能にします。

## 特徴

- **様々な種類のケーブル**: ガラスケーブル、カバーケーブル、スマートケーブル、高密度カバーケーブル、高密度スマートケーブルに対応
- **複数の設置モード**:
  - **直線**: ケーブルを素早く直線に設置
  - **敷き詰め**: ケーブルを長方形の範囲に設置
  - **分岐**: ケーブルを枝状に分岐させて設置
- **ネットワーク統合**: <ItemLink id="ae2:wireless_access_point" />を使用してMEネットワークに接続
- **元に戻す(Undo)に対応**: 直近の設置操作の取り消しに対応

## 使用方法

- **右クリック**: 座標を選択
- **左クリック**: 座標選択を取り消し
- **Gキー**: 設定GUIを開く
- **手に持ってCtrl + 左クリック**: 直近の設置操作を取り消す（Undo）
- **ME無線アクセスポイントにセット**: MEネットワークにリンク
### 色彩の鍵非使用時

![色彩の鍵非使用時](../../assets/me_cable_placement_tool_gui_no_key.png)

- **手動染色**: オフハンドに染料を持っている場合、染料を消費し設置したケーブルを自動的に着色します

### 色彩の鍵使用時

![色彩の鍵使用時](../../assets/me_cable_placement_tool_gui_with_key.png)

- **色選択**: 設定GUIから16色を自由に選択できます
- **自動染色**: 設置したケーブルは、染料を消費せず自動的に選択した色になります
- **Aキー**: 現在選択されている色をブックマークに登録します（キー設定で変更可能）

## レシピ

<RecipeFor id="meplacementtool:me_cable_placement_tool" />