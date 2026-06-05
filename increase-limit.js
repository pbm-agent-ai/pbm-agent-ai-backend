const { ethers } = require('ethers');
const PRIVATE_KEY = '1533eb22331231555b642165b9c1ae2df192c7de0a48c782327660055e882e5e';
const provider = new ethers.JsonRpcProvider('https://eth-sepolia.g.alchemy.com/v2/48rdcPmOO2K1i8uWVwVss');
const wallet = new ethers.Wallet(PRIVATE_KEY, provider);
const ABI = [
  "function walletLimit() view returns (uint256)",
  "function totalAllocated() view returns (uint256)",
  "function updateWalletLimit(uint256) external"
];
const contract = new ethers.Contract('0x5aef3f64351a4c5903ad9d1e4a2a8f89760e28f1', ABI, wallet);

async function main() {
  const D = 10n**18n;
  const NEW_LIMIT = 1_000_000n * D; // 100만 KRW로 늘리기 (테스트 여유분 확보)

  const tx = await contract.updateWalletLimit(NEW_LIMIT, { gasLimit: 100000 });
  console.log(`tx: ${tx.hash}`);
  await tx.wait();
  
  const [limit, allocated] = await Promise.all([contract.walletLimit(), contract.totalAllocated()]);
  console.log(`✅ walletLimit: ${limit/D} KRW, totalAllocated: ${allocated/D} KRW, 가용: ${(limit-allocated)/D} KRW`);
}
main().catch(console.error);
