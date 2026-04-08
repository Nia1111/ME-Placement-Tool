---
navigation:
  parent: introduction/index.md
  title: ME設置ツール
  position: 1
  icon: meplacementtool:me_placement_tool
categories:
  - meplacementtool tools
item_ids:
  - meplacementtool:me_placement_tool
---

# ME設置ツール

<ItemImage id="meplacementtool:me_placement_tool" scale="4" />

ME設置ツールは、MEネットワークからブロックやAE2のケーブル、液体を直接配置できる強力なネットワーク統合ツールです。最大18種類の対象アイテムや液体を設定して利用することができます。

## 機能

- **ワイヤレス配置**: ブロックやパーツをインベントリに入れずに配置できます
- **ネットワーク統合**: <ItemLink id="ae2:wireless_access_point" />を介してMEネットワークに接続します
- **18個の設定スロット**: 最大18種類のアイテムまたは液体を設定して、素早く切り替えられます
- **自動クラフト**: アイテムが手元になくても作成可能な場合は、自動クラフト注文画面を開きます
- **メモリーカード対応**: <ItemLink id="ae2:memory_card" />をオフハンドに持っている場合、デバイスを配置する際にその設定を自動的に適用します
- **Mekanismの設定カード対応**: オフハンドにMekanismの設定カードを持っている場合、デバイスを配置する際にその設定を自動的に適用します

## 使用方法

- **ツールを持って右クリック(空中)**: 設定GUIを開く
- **ツールを持って右クリック(ブロック)**: ブロックを設置
- **Gを長押し**: ラジアルメニューを開く
- **ラジアルメニューで左クリック**: アイテムを選択

![ラジアルメニュー](../../assets/me_placement_tool_radial.png)

## ネットワーク接続

ツールを<ItemLink id="ae2:wireless_access_point" />にセットするとMEネットワークに接続できます。

## 設定GUI

![設定GUI](../../assets/me_placement_tool_gui.png)

設定GUIには、アイテムを配置するスロットが18個あります:
- JEI/REIからアイテムをドラッグ＆ドロップしてターゲットに設定できます
- インベントリからアイテムをドラッグ＆ドロップしてターゲットに設定できます
- ブロックだけでなく、AE2のパーツ（ケーブル、バス、パネルなど）も設定可能です

## レシピ

<RecipeFor id="meplacementtool:me_placement_tool" />
