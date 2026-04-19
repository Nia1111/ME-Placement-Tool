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

MEケーブル設置ツールは、迅速な配線のために設計されており、多様なケーブルの種類や色、複数の設置モードに対応しています。

## 機能

- **複数のケーブルタイプに対応**: ガラスケーブル、カバーケーブル、スマートケーブル、高密度カバーケーブル、高密度スマートケーブルに対応
- **複数の配置モード**:
  - **直線**: ケーブルを直線に素早く設置できます
  - **敷き詰め**: 長方形の範囲にケーブルネットを設置します
  - **分岐**: ケーブルを樹状に分岐させて設置します
- **ネットワーク統合**: <ItemLink id="ae2:wireless_access_point" />を介してMEネットワークに接続します
- **元に戻す(Undo)に対応**: 直近の設置操作の取り消しに対応しています

## 使用方法

- **右クリック**: 座標を選択
- **左クリック**: 座標選択をキャンセル
- **Gキーを押す**: 設定GUIを開く
- **ツールを持ってCtrl + 左クリック**: 直近の設置操作を取り消す（Undo）
- **ME無線アクセスポイントにセット**: MEネットワークにリンク
### 色彩の鍵非使用時

![色彩の鍵非使用時](../../assets/me_cable_placement_tool_gui_no_key.png)

- **手動染色**: オフハンドに染料を持っている場合、染料を消費し設置されたケーブルを自動的に着色します

### 色彩の鍵使用時

![色彩の鍵使用時](../../assets/me_cable_placement_tool_gui_with_key.png)

- **色選択**: 設定GUIから16色のうち好きな色を自由に選択できます
- **自動染色**: 配置されたケーブルは、染料を消費することなく、自動的に選択した色になります
- **Aキーを押す**: 現在選択されている色をブックマークに登録します（キー設定で変更可能です）

## レシピ

<RecipeFor id="meplacementtool:me_cable_placement_tool" />