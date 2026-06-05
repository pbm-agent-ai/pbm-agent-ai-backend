/**
 * PBMSmartAccount의 walletLimit을 올바른 값으로 수정하는 스크립트.
 * walletLimit이 0으로 설정되어 addSessionKey가 항상 revert되는 문제를 해결한다.
 */
const { ethers } = require('ethers');

const PRIVATE_KEY = '1533eb22331231555b642165b9c1ae2df192c7de0a48c782327660055e882e5e';
const RPC_URL = 'https://eth-sepolia.g.alchemy.com/v2/48rdcPmOO2K1i8uWVwVss';
const SMART_WALLET = '0x5aef3f64351a4c5903ad9d1e4a2a8f89760e28f1';
const NEW_LIMIT_KRW = 50000n;
const DECIMALS = 10n ** 18n;
const NEW_LIMIT_WEI = NEW_LIMIT_KRW * DECIMALS;

// updateWalletLimit(uint256) ABI
const ABI = ["function updateWalletLimit(uint256 _newWalletLimit) external",
             "function walletLimit() view returns (uint256)"];

async function fixWalletLimit() {
  const provider = new ethers.JsonRpcProvider(RPC_URL);
  const wallet = new ethers.Wallet(PRIVATE_KEY, provider);
  const contract = new ethers.Contract(SMART_WALLET, ABI, wallet);

  const before = await contract.walletLimit();
  console.log(`현재 walletLimit: ${before} wei = ${before / DECIMALS} KRW`);

  console.log(`\nwalletLimit을 ${NEW_LIMIT_KRW} KRW (${NEW_LIMIT_WEI} wei)으로 수정 중...`);
  const tx = await contract.updateWalletLimit(NEW_LIMIT_WEI, { gasLimit: 100000 });
  console.log(`tx 전송: ${tx.hash}`);
  await tx.wait();

  const after = await contract.walletLimit();
  console.log(`\n✅ 수정 완료! walletLimit: ${after} wei = ${after / DECIMALS} KRW`);
}

fixWalletLimit().catch(console.error);
